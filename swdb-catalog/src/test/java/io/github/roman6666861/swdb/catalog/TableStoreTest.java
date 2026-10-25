package io.github.roman6666861.swdb.catalog;

import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
import io.github.roman6666861.swdb.storage.KeyBuilder;
import io.github.roman6666861.swdb.storage.KvIterator;
import io.github.roman6666861.swdb.storage.KvStore;
import io.github.roman6666861.swdb.storage.KvTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Чтение по первичному ключу (точечное и по диапазону), замена строк и работа в транзакциях.
 * Если не сказано иное, всё происходит в одной транзакции теста {@link #tx}.
 */
class TableStoreTest {

    @TempDir
    Path dir;

    private KvStore store;
    private Catalog catalog;
    private KvTransaction tx;

    @BeforeEach
    void open() {
        store = KvStore.open(dir);
        catalog = new Catalog(store);
        tx = catalog.begin();
    }

    @AfterEach
    void close() {
        tx.close();
        store.close();
    }

    @Test
    void changesAreVisibleToOtherTransactionsAfterCommit() {
        TableStore t = intTable(1);
        try (KvTransaction other = catalog.begin()) {
            assertThat(t.get(other, 1)).as("not committed yet").isEmpty();
        }

        tx.commit();

        try (KvTransaction other = catalog.begin()) {
            assertThat(t.get(other, 1)).isPresent();
        }
    }

    @Test
    void rollbackUndoesAllChangesOfTransaction() {
        TableStore t = intTable(1, 2);
        tx.commit();
        try (KvTransaction change = catalog.begin()) {
            t.insert(change, new Object[]{3, "v3"});
            t.delete(change, new Object[]{1, "v1"});
            t.update(change, List.of(change(2, "v2", 2, "x")));
            change.rollback();
        }

        try (KvTransaction read = catalog.begin()) {
            assertThat(rows(t.scan(read))).containsExactly(List.of(1, "v1"), List.of(2, "v2"));
        }
    }

    @Test
    void sameNewKeyInTwoTransactionsWaitsForLock() {
        TableStore t = intTable();
        tx.commit();
        try (KvTransaction first = catalog.begin(); KvTransaction second = store.begin(Duration.ofMillis(100))) {
            t.insert(first, new Object[]{5, "first"});

            // Ключа ещё нет в базе, но проверка уникальности его заблокировала.
            assertThatThrownBy(() -> t.insert(second, new Object[]{5, "second"}))
                    .isInstanceOfSatisfying(SwdbException.class,
                            e -> assertThat(e.sqlState()).isEqualTo(SqlStates.LOCK_NOT_AVAILABLE));
        }
    }

    @Test
    void getByPrimaryKey() {
        TableStore t = intTable(1, 2, 3);

        assertThat(t.get(tx, 2)).hasValueSatisfying(row -> assertThat(row).containsExactly(2, "v2"));
        assertThat(t.get(tx, 2L)).isPresent(); // число другого типа приводится к INTEGER
        assertThat(t.get(tx, 4)).isEmpty();
    }

    @Test
    void scanRangeWithInclusiveAndExclusiveBounds() {
        TableStore t = intTable(-5, 1, 2, 3, 4, 10);

        assertThat(ids(t.scan(tx, KeyBound.inclusive(2), KeyBound.inclusive(4)))).containsExactly(2, 3, 4);
        assertThat(ids(t.scan(tx, KeyBound.exclusive(2), KeyBound.exclusive(4)))).containsExactly(3);
        assertThat(ids(t.scan(tx, KeyBound.inclusive(3), null))).containsExactly(3, 4, 10);
        assertThat(ids(t.scan(tx, null, KeyBound.exclusive(1)))).containsExactly(-5);
        assertThat(ids(t.scan(tx, KeyBound.inclusive(5), KeyBound.inclusive(9)))).isEmpty();
        assertThat(ids(t.scan(tx, KeyBound.inclusive(4), KeyBound.inclusive(2)))).isEmpty();
    }

    @Test
    void scanDoesNotLeakIntoNeighbourTables() {
        TableStore first = intTable(1, 2);
        catalog.createTable("other", List.of(new ColumnDef("id", ColumnType.INTEGER, -1, -1, false)),
                List.of("id"), false);
        catalog.table("other").orElseThrow().insert(tx, new Object[]{1});

        assertThat(ids(first.scan(tx, KeyBound.inclusive(2), null))).containsExactly(2);
        assertThat(ids(first.scan(tx, null, null))).containsExactly(1, 2);
    }

    @Test
    void stringKeyRange() {
        catalog.createTable("words", List.of(new ColumnDef("w", ColumnType.VARCHAR, 10, -1, false)),
                List.of("w"), false);
        TableStore t = catalog.table("words").orElseThrow();
        for (String w : List.of("a", "ab", "abc", "b", "ba")) {
            t.insert(tx, new Object[]{w});
        }

        // Строки сравниваются целиком: "ab" < "abc" < "b", но "abc" не начинается с ключа "ab".
        assertThat(firsts(t.scan(tx, KeyBound.inclusive("ab"), KeyBound.inclusive("b")))).containsExactly("ab", "abc", "b");
        assertThat(firsts(t.scan(tx, KeyBound.exclusive("ab"), KeyBound.exclusive("b")))).containsExactly("abc");
    }

    @Test
    void compositeKeyPrefixSelectsAllRowsWithLeadingValue() {
        catalog.createTable("events", List.of(
                        new ColumnDef("day", ColumnType.INTEGER, -1, -1, false),
                        new ColumnDef("seq", ColumnType.INTEGER, -1, -1, false)),
                List.of("day", "seq"), false);
        TableStore t = catalog.table("events").orElseThrow();
        for (int day = 1; day <= 3; day++) {
            for (int seq = 1; seq <= 3; seq++) {
                t.insert(tx, new Object[]{day, seq});
            }
        }

        // day = 2: граница-префикс включает все seq этого дня.
        assertThat(rows(t.scan(tx, KeyBound.inclusive(2), KeyBound.inclusive(2))))
                .containsExactly(List.of(2, 1), List.of(2, 2), List.of(2, 3));
        // day > 2, хотя в границе нет seq.
        assertThat(rows(t.scan(tx, KeyBound.exclusive(2), null))).hasSize(3).allMatch(row -> row.getFirst().equals(3));
        // day = 2 AND seq >= 2.
        assertThat(rows(t.scan(tx, KeyBound.inclusive(2, 2), KeyBound.inclusive(2))))
                .containsExactly(List.of(2, 2), List.of(2, 3));
        assertThat(t.get(tx, 3, 1)).isPresent();
    }

    @Test
    void updateKeepingKeyReplacesRow() {
        TableStore t = intTable(1, 2);

        assertThat(t.update(tx, List.of(change(1, "v1", 1, "x")))).isEqualTo(1);

        assertThat(rows(t.scan(tx))).containsExactly(List.of(1, "x"), List.of(2, "v2"));
    }

    @Test
    void updateOfPrimaryKeyMovesRow() {
        TableStore t = intTable(1, 2);

        t.update(tx, List.of(change(1, "v1", 5, "v1")));

        assertThat(rows(t.scan(tx))).containsExactly(List.of(2, "v2"), List.of(5, "v1"));
        assertThat(t.get(tx, 1)).isEmpty();
    }

    @Test
    void keysCanBeShiftedOntoEachOther() {
        TableStore t = intTable(1, 2, 3);

        // Как UPDATE t SET id = id + 1: ключ 2 занят, пока его не освободит вторая строка.
        t.update(tx, List.of(change(1, "v1", 2, "v1"), change(2, "v2", 3, "v2"), change(3, "v3", 4, "v3")));

        assertThat(rows(t.scan(tx))).containsExactly(List.of(2, "v1"), List.of(3, "v2"), List.of(4, "v3"));
    }

    @Test
    void duplicateKeyRejectsWholeUpdate() {
        TableStore t = intTable(1, 2, 3);

        // Ключ 3 занят строкой, которая не меняется.
        assertThatThrownBy(() -> t.update(tx, List.of(change(1, "v1", 0, "v1"), change(2, "v2", 3, "v2"))))
                .isInstanceOfSatisfying(SwdbException.class,
                        e -> assertThat(e.sqlState()).isEqualTo(SqlStates.UNIQUE_VIOLATION));
        // Две строки на один ключ.
        assertThatThrownBy(() -> t.update(tx, List.of(change(1, "v1", 7, "v1"), change(2, "v2", 7, "v2"))))
                .isInstanceOfSatisfying(SwdbException.class,
                        e -> assertThat(e.sqlState()).isEqualTo(SqlStates.UNIQUE_VIOLATION));

        assertThat(rows(t.scan(tx))).containsExactly(List.of(1, "v1"), List.of(2, "v2"), List.of(3, "v3"));
    }

    @Test
    void invalidValueRejectsWholeUpdate() {
        TableStore t = intTable(1, 2);

        assertThatThrownBy(() -> t.update(tx, List.of(change(1, "v1", 1, "ok"), change(2, "v2", 2, "far too long"))))
                .isInstanceOfSatisfying(SwdbException.class,
                        e -> assertThat(e.sqlState()).isEqualTo(SqlStates.STRING_DATA_RIGHT_TRUNCATION));

        assertThat(rows(t.scan(tx))).containsExactly(List.of(1, "v1"), List.of(2, "v2"));
    }

    @Test
    void rowDeletedSinceReadIsSkipped() {
        TableStore t = intTable(1, 2);
        t.delete(tx, new Object[]{1, "v1"});

        assertThat(t.update(tx, List.of(change(1, "v1", 1, "x"), change(2, "v2", 2, "y")))).isEqualTo(1);

        assertThat(rows(t.scan(tx))).containsExactly(List.of(2, "y"));
    }

    @Test
    void insertAndDeleteMaintainIndexes() {
        TableStore t = indexedTable();
        t.insert(tx, new Object[]{1, "b"});
        t.insert(tx, new Object[]{2, "a"});
        t.insert(tx, new Object[]{3, null});
        assertIndexesMatchRows(t, 3);

        assertThat(t.delete(tx, new Object[]{2, "ignored"})).isTrue(); // старые значения читаются из хранилища
        assertIndexesMatchRows(t, 2);
    }

    @Test
    void updateMaintainsIndexes() {
        TableStore t = indexedTable();
        for (int id = 1; id <= 4; id++) {
            t.insert(tx, new Object[]{id, "same"});
        }

        t.update(tx, List.of(change(1, "same", 1, "x")));                         // значение колонки
        t.update(tx, List.of(change(2, "same", 2, null)));                        // в NULL
        t.update(tx, List.of(change(3, "same", 4, "same"), change(4, "same", 3, "same"))); // обмен ключами
        assertIndexesMatchRows(t, 4);

        // Как UPDATE t SET id = id + 1.
        t.update(tx, List.of(change(1, "x", 2, "x"), change(2, null, 3, null),
                change(3, "same", 4, "same"), change(4, "same", 5, "same")));
        assertIndexesMatchRows(t, 4);
    }

    @Test
    void rollbackLeavesNoIndexEntries() {
        TableStore t = indexedTable();
        t.insert(tx, new Object[]{1, "a"});
        tx.commit();
        try (KvTransaction change = catalog.begin()) {
            t.insert(change, new Object[]{2, "b"});
            t.update(change, List.of(change(1, "a", 1, "c")));
            change.rollback();
        }

        tx.close();
        tx = catalog.begin();
        assertIndexesMatchRows(t, 1);
        assertThat(rows(t.scan(tx))).containsExactly(List.of(1, "a"));
    }

    /** Таблица {@code t(id, v)} с индексами по {@code (v)} и {@code (v, id)}, без строк. */
    private TableStore indexedTable() {
        intTable();
        catalog.createIndex("t", "t_v", List.of("v"), false);
        catalog.createIndex("t", "t_v_id", List.of("v", "id"), false);
        return catalog.table("t").orElseThrow();
    }

    /** В каждом индексе ровно те записи, которые построил бы по строкам таблицы {@code createIndex}. */
    private void assertIndexesMatchRows(TableStore t, int rowCount) {
        TableDef table = t.table();
        RowCodec codec = new RowCodec(table);
        HexFormat hex = HexFormat.of();
        for (IndexDef index : table.indexes()) {
            List<String> expected = new ArrayList<>();
            try (RowCursor cursor = t.scan(tx)) {
                while (cursor.next()) {
                    expected.add(hex.formatHex(codec.encodeIndexKey(index, cursor.row()))
                            + " -> " + hex.formatHex(codec.encodeKey(cursor.row())));
                }
            }
            // Шестнадцатеричные строки сортируются так же, как байты в RocksDB.
            expected.sort(null);
            List<String> actual = new ArrayList<>();
            byte[] prefix = Keys.indexPrefix(table.id(), index.id());
            try (KvIterator it = tx.scan(prefix, KeyBuilder.prefixEnd(prefix))) {
                while (it.next()) {
                    actual.add(hex.formatHex(it.key()) + " -> " + hex.formatHex(it.value()));
                }
            }
            assertThat(actual).as(index.name()).hasSize(rowCount).containsExactlyElementsOf(expected);
        }
    }

    private static TableStore.Change change(int oldId, String oldV, int newId, String newV) {
        return new TableStore.Change(new Object[]{oldId, oldV}, new Object[]{newId, newV});
    }

    /** Таблица {@code t(id INT, v VARCHAR)} со строками {@code (id, "v" + id)}. */
    private TableStore intTable(int... ids) {
        catalog.createTable("t", List.of(
                        new ColumnDef("id", ColumnType.INTEGER, -1, -1, false),
                        new ColumnDef("v", ColumnType.VARCHAR, 10, -1, true)),
                List.of("id"), false);
        TableStore t = catalog.table("t").orElseThrow();
        for (int id : ids) {
            t.insert(tx, new Object[]{id, "v" + id});
        }
        return t;
    }

    private static List<Integer> ids(RowCursor cursor) {
        return firsts(cursor).stream().map(Integer.class::cast).toList();
    }

    private static List<Object> firsts(RowCursor cursor) {
        return rows(cursor).stream().map(List::getFirst).toList();
    }

    private static List<List<Object>> rows(RowCursor cursor) {
        List<List<Object>> rows = new ArrayList<>();
        try (cursor) {
            while (cursor.next()) {
                rows.add(List.of(cursor.row()));
            }
        }
        return rows;
    }
}
