package io.github.roman6666861.swdb.catalog;

import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
import io.github.roman6666861.swdb.storage.KvStore;
import io.github.roman6666861.swdb.storage.KvTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogTest {

    private static final List<ColumnDef> USERS = List.of(
            new ColumnDef("id", ColumnType.INTEGER, -1, -1, true),
            new ColumnDef("name", ColumnType.VARCHAR, 5, -1, true),
            new ColumnDef("balance", ColumnType.DECIMAL, 10, 2, true));

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
    void createInsertScanInPrimaryKeyOrder() {
        catalog.createTable("users", USERS, List.of("id"), false);
        TableStore users = catalog.table("users").orElseThrow();

        insert(users, new Object[]{3, "carol", new BigDecimal("30.00")});
        insert(users, new Object[]{-1, "ann", null});
        insert(users, new Object[]{2, "bob", new BigDecimal("20.50")});

        assertThat(rows(users)).containsExactly(
                new Object[]{-1, "ann", null},
                new Object[]{2, "bob", new BigDecimal("20.50")},
                new Object[]{3, "carol", new BigDecimal("30.00")});
    }

    @Test
    void primaryKeyColumnsBecomeNotNull() {
        catalog.createTable("users", USERS, List.of("id"), false);

        assertThat(catalog.table("users").orElseThrow().table().columns().getFirst().nullable()).isFalse();
        assertThatThrownBy(() -> insert(catalog.table("users").orElseThrow(), new Object[]{null, "x", null}))
                .satisfies(e -> assertSqlState(e, SqlStates.NOT_NULL_VIOLATION));
    }

    @Test
    void duplicatePrimaryKeyIsRejected() {
        catalog.createTable("users", USERS, List.of("id"), false);
        TableStore users = catalog.table("users").orElseThrow();
        insert(users, new Object[]{1, "ann", null});

        assertThatThrownBy(() -> insert(users, new Object[]{1, "bob", null}))
                .satisfies(e -> assertSqlState(e, SqlStates.UNIQUE_VIOLATION));
    }

    @Test
    void varcharLengthIsEnforced() {
        catalog.createTable("users", USERS, List.of("id"), false);

        assertThatThrownBy(() -> insert(catalog.table("users").orElseThrow(), new Object[]{1, "toolong", null}))
                .satisfies(e -> assertSqlState(e, SqlStates.STRING_DATA_RIGHT_TRUNCATION));
    }

    @Test
    void tablesAndRowsSurviveRestart() {
        catalog.createTable("users", USERS, List.of("id"), false);
        insert(catalog.table("users").orElseThrow(), new Object[]{1, "ann", BigDecimal.ONE});
        store.close();

        store = KvStore.open(dir);
        catalog = new Catalog(store);

        TableStore users = catalog.table("users").orElseThrow();
        assertThat(users.table().columns()).extracting(ColumnDef::name).containsExactly("id", "name", "balance");
        assertThat(rows(users)).containsExactly(new Object[]{1, "ann", BigDecimal.ONE});
    }

    @Test
    void dropRemovesDataAndIdIsNotConfused() {
        catalog.createTable("a", USERS, List.of("id"), false);
        insert(catalog.table("a").orElseThrow(), new Object[]{1, "x", null});
        catalog.createTable("b", USERS, List.of("id"), false);
        insert(catalog.table("b").orElseThrow(), new Object[]{2, "y", null});

        catalog.dropTable("a", false);

        assertThat(catalog.table("a")).isEmpty();
        assertThat(rows(catalog.table("b").orElseThrow())).containsExactly(new Object[]{2, "y", null});
        assertThatThrownBy(() -> catalog.dropTable("a", false))
                .satisfies(e -> assertSqlState(e, SqlStates.UNDEFINED_TABLE));
        assertThat(catalog.dropTable("a", true)).isFalse();
    }

    @Test
    void ddlErrors() {
        catalog.createTable("users", USERS, List.of("id"), false);

        assertThatThrownBy(() -> catalog.createTable("users", USERS, List.of("id"), false))
                .satisfies(e -> assertSqlState(e, SqlStates.DUPLICATE_TABLE));
        assertThat(catalog.createTable("users", USERS, List.of("id"), true)).isFalse();
        assertThatThrownBy(() -> catalog.createTable("t", USERS, List.of(), false))
                .satisfies(e -> assertSqlState(e, SqlStates.FEATURE_NOT_SUPPORTED));
        assertThatThrownBy(() -> catalog.createTable("t", USERS, List.of("nope"), false))
                .satisfies(e -> assertSqlState(e, SqlStates.UNDEFINED_COLUMN));
        assertThatThrownBy(() -> catalog.createTable("t", USERS, List.of("balance"), false))
                .satisfies(e -> assertSqlState(e, SqlStates.FEATURE_NOT_SUPPORTED));
    }

    /** Вставка в отдельной транзакции с COMMIT. */
    private void insert(TableStore table, Object[] row) {
        try (KvTransaction tx = catalog.begin()) {
            table.insert(tx, row);
            tx.commit();
        }
    }

    private List<Object[]> rows(TableStore table) {
        List<Object[]> rows = new ArrayList<>();
        try (KvTransaction tx = catalog.begin(); RowCursor cursor = table.scan(tx)) {
            while (cursor.next()) {
                rows.add(cursor.row());
            }
        }
        return rows;
    }

    private static void assertSqlState(Throwable e, String sqlState) {
        assertThat(e).isInstanceOf(SwdbException.class);
        assertThat(((SwdbException) e).sqlState()).isEqualTo(sqlState);
    }
}
