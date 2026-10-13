package io.github.roman6666861.swdb.catalog;

import io.github.roman6666861.swdb.storage.KvStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Чтение по первичному ключу: точечное и по диапазону.
 */
class TableStoreTest {

    @TempDir
    Path dir;

    private KvStore store;
    private Catalog catalog;

    @BeforeEach
    void open() {
        store = KvStore.open(dir);
        catalog = new Catalog(store);
    }

    @AfterEach
    void close() {
        store.close();
    }

    @Test
    void getByPrimaryKey() {
        TableStore t = intTable(1, 2, 3);

        assertThat(t.get(2)).hasValueSatisfying(row -> assertThat(row).containsExactly(2, "v2"));
        assertThat(t.get(2L)).isPresent(); // число другого типа приводится к INTEGER
        assertThat(t.get(4)).isEmpty();
    }

    @Test
    void scanRangeWithInclusiveAndExclusiveBounds() {
        TableStore t = intTable(-5, 1, 2, 3, 4, 10);

        assertThat(ids(t.scan(KeyBound.inclusive(2), KeyBound.inclusive(4)))).containsExactly(2, 3, 4);
        assertThat(ids(t.scan(KeyBound.exclusive(2), KeyBound.exclusive(4)))).containsExactly(3);
        assertThat(ids(t.scan(KeyBound.inclusive(3), null))).containsExactly(3, 4, 10);
        assertThat(ids(t.scan(null, KeyBound.exclusive(1)))).containsExactly(-5);
        assertThat(ids(t.scan(KeyBound.inclusive(5), KeyBound.inclusive(9)))).isEmpty();
        assertThat(ids(t.scan(KeyBound.inclusive(4), KeyBound.inclusive(2)))).isEmpty();
    }

    @Test
    void scanDoesNotLeakIntoNeighbourTables() {
        TableStore first = intTable(1, 2);
        catalog.createTable("other", List.of(new ColumnDef("id", ColumnType.INTEGER, -1, -1, false)),
                List.of("id"), false);
        catalog.table("other").orElseThrow().insert(new Object[]{1});

        assertThat(ids(first.scan(KeyBound.inclusive(2), null))).containsExactly(2);
        assertThat(ids(first.scan(null, null))).containsExactly(1, 2);
    }

    @Test
    void stringKeyRange() {
        catalog.createTable("words", List.of(new ColumnDef("w", ColumnType.VARCHAR, 10, -1, false)),
                List.of("w"), false);
        TableStore t = catalog.table("words").orElseThrow();
        for (String w : List.of("a", "ab", "abc", "b", "ba")) {
            t.insert(new Object[]{w});
        }

        // Строки сравниваются целиком: "ab" < "abc" < "b", но "abc" не начинается с ключа "ab".
        assertThat(firsts(t.scan(KeyBound.inclusive("ab"), KeyBound.inclusive("b")))).containsExactly("ab", "abc", "b");
        assertThat(firsts(t.scan(KeyBound.exclusive("ab"), KeyBound.exclusive("b")))).containsExactly("abc");
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
                t.insert(new Object[]{day, seq});
            }
        }

        // day = 2: граница-префикс включает все seq этого дня.
        assertThat(rows(t.scan(KeyBound.inclusive(2), KeyBound.inclusive(2))))
                .containsExactly(List.of(2, 1), List.of(2, 2), List.of(2, 3));
        // day > 2, хотя в границе нет seq.
        assertThat(rows(t.scan(KeyBound.exclusive(2), null))).hasSize(3).allMatch(row -> row.getFirst().equals(3));
        // day = 2 AND seq >= 2.
        assertThat(rows(t.scan(KeyBound.inclusive(2, 2), KeyBound.inclusive(2))))
                .containsExactly(List.of(2, 2), List.of(2, 3));
        assertThat(t.get(3, 1)).isPresent();
    }

    /** Таблица {@code t(id INT, v VARCHAR)} со строками {@code (id, "v" + id)}. */
    private TableStore intTable(int... ids) {
        catalog.createTable("t", List.of(
                        new ColumnDef("id", ColumnType.INTEGER, -1, -1, false),
                        new ColumnDef("v", ColumnType.VARCHAR, 10, -1, true)),
                List.of("id"), false);
        TableStore t = catalog.table("t").orElseThrow();
        for (int id : ids) {
            t.insert(new Object[]{id, "v" + id});
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
