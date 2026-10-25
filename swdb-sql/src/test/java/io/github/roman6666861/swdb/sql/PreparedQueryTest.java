package io.github.roman6666861.swdb.sql;

import io.github.roman6666861.swdb.catalog.Catalog;
import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
import io.github.roman6666861.swdb.storage.KvStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Подготовленные операторы: разобрать один раз, выполнить много раз с разными параметрами.
 */
class PreparedQueryTest {

    @TempDir
    Path dir;

    private KvStore store;
    private SqlSession session;

    @BeforeEach
    void open() {
        store = KvStore.open(dir);
        session = new SqlEngine(new Catalog(store)).openSession();
        session.execute("CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(10), price DECIMAL(5, 2), d DATE)");
        session.execute("INSERT INTO t VALUES (1, 'ann', 1.50, DATE '2026-01-01'), (2, 'bob', 2.00, NULL)");
    }

    @AfterEach
    void close() {
        session.close();
        store.close();
    }

    @Test
    void selectWithParameterRunsManyTimes() {
        try (PreparedQuery query = session.prepare("SELECT name FROM t WHERE id = $1")) {
            // Calcite вывел тип параметра из колонки id и знает колонки результата до выполнения.
            assertThat(query.parameterTypes()).containsExactly(Types.INTEGER);
            assertThat(query.columns()).containsExactly(new Column("name", Types.VARCHAR));

            assertThat(rows(query.execute(List.of(1)))).containsExactly(List.of("ann"));
            assertThat(rows(query.execute(List.of(2)))).containsExactly(List.of("bob"));
            assertThat(rows(query.execute(List.of(3)))).isEmpty();
        }
    }

    @Test
    void dmlWithParametersOfDifferentTypes() {
        try (PreparedQuery insert = session.prepare("INSERT INTO t VALUES ($1, $2, $3, $4)")) {
            assertThat(insert.parameterTypes()).containsExactly(Types.INTEGER, Types.VARCHAR, Types.DECIMAL, Types.DATE);
            assertThat(insert.columns()).isEmpty();

            assertThat(insert.execute(List.of(3, "carol", new BigDecimal("3.25"), Date.valueOf("2026-02-03"))))
                    .isEqualTo(new StatementResult.Update("INSERT", 1));
            assertThat(insert.execute(Arrays.asList(4, null, null, null)))
                    .isEqualTo(new StatementResult.Update("INSERT", 1));
        }
        try (PreparedQuery update = session.prepare("UPDATE t SET price = price + $2 WHERE id = $1")) {
            assertThat(update.parameterTypes()).containsExactly(Types.INTEGER, Types.DECIMAL);
            assertThat(update.execute(List.of(3, new BigDecimal("1")))).isEqualTo(new StatementResult.Update("UPDATE", 1));
        }

        assertThat(rows(session.execute("SELECT id, name, price, d FROM t WHERE id > 2"))).containsExactly(
                List.of(3, "carol", new BigDecimal("4.25"), Date.valueOf("2026-02-03")),
                Arrays.asList(4, null, null, null));
    }

    @Test
    void repeatedAndReorderedParameters() {
        try (PreparedQuery query = session.prepare("SELECT id FROM t WHERE name = $2 OR id = $1 OR id + 1 = $1 ORDER BY id")) {
            assertThat(query.parameterTypes()).containsExactly(Types.INTEGER, Types.VARCHAR);

            assertThat(rows(query.execute(List.of(2, "nobody")))).containsExactly(List.of(1), List.of(2));
            assertThat(rows(query.execute(List.of(9, "bob")))).containsExactly(List.of(2));
        }
    }

    @Test
    void parameterValueIsNeverParsedAsSql() {
        try (PreparedQuery query = session.prepare("SELECT id FROM t WHERE name = $1")) {
            assertThat(rows(query.execute(List.of("x' OR '1'='1")))).isEmpty();
        }
        assertThat(rows(session.execute("SELECT COUNT(*) FROM t"))).containsExactly(List.of(2L));
    }

    @Test
    void wrongNumberOfParameters() {
        try (PreparedQuery query = session.prepare("SELECT name FROM t WHERE id = $1")) {
            assertSqlState(() -> query.execute(List.of()), SqlStates.PROTOCOL_VIOLATION);
            assertSqlState(() -> query.execute(List.of(1, 2)), SqlStates.PROTOCOL_VIOLATION);
        }
    }

    @Test
    void otherStatementsArePreparedWithoutParameters() {
        assertThat(session.prepare("BEGIN").execute(List.of())).isEqualTo(new StatementResult.Update("BEGIN", 0));
        assertThat(session.prepare("SET application_name = 'app'").execute(List.of()))
                .isEqualTo(new StatementResult.Update("SET", 0));
        PreparedQuery explain = session.prepare("EXPLAIN SELECT * FROM t WHERE id = 1");
        assertThat(explain.columns()).containsExactly(new Column("QUERY PLAN", Types.VARCHAR));
        assertThat(rows(explain.execute(List.of()))).isNotEmpty();
        assertThat(session.prepare("").execute(List.of())).isEqualTo(new StatementResult.Empty());
        assertThat(session.prepare("ROLLBACK").execute(List.of())).isEqualTo(new StatementResult.Update("ROLLBACK", 0));

        assertSqlState(() -> session.prepare("EXPLAIN SELECT * FROM t WHERE id = $1"), SqlStates.FEATURE_NOT_SUPPORTED);
    }

    @Test
    void preparedStatementsRunInSessionTransaction() {
        try (PreparedQuery insert = session.prepare("INSERT INTO t (id, name) VALUES ($1, $2)")) {
            session.execute("BEGIN");
            insert.execute(List.of(3, "carol"));
            assertThat(rows(session.execute("SELECT COUNT(*) FROM t"))).containsExactly(List.of(3L));

            // Ошибка ломает транзакцию так же, как у обычных операторов.
            assertSqlState(() -> insert.execute(List.of(1, "dup")), SqlStates.UNIQUE_VIOLATION);
            assertSqlState(() -> insert.execute(List.of(4, "x")), SqlStates.IN_FAILED_SQL_TRANSACTION);
            assertSqlState(() -> session.prepare("SELECT 1"), SqlStates.IN_FAILED_SQL_TRANSACTION);

            // А ROLLBACK подготовить и выполнить можно.
            session.prepare("ROLLBACK").execute(List.of());
        }
        assertThat(rows(session.execute("SELECT COUNT(*) FROM t"))).containsExactly(List.of(2L));
    }

    @Test
    void primaryKeyLookupsWithParametersOfAllKeyTypes() {
        session.execute("""
                CREATE TABLE k (d DATE, ts TIMESTAMP, code VARCHAR(5), n BIGINT, note VARCHAR(10),
                  PRIMARY KEY (d, ts, code, n))""");
        session.execute("""
                INSERT INTO k VALUES
                  (DATE '2026-10-20', TIMESTAMP '2026-10-20 08:00:00', 'a', 1, 'one'),
                  (DATE '2026-10-21', TIMESTAMP '2026-10-21 09:30:00', 'b', 2, 'two'),
                  (DATE '2026-10-21', TIMESTAMP '2026-10-21 09:30:00', 'b', 3, 'three'),
                  (DATE '2026-10-22', TIMESTAMP '2026-10-22 10:00:00', 'c', 4, 'four')""");

        // Если значение параметра переведено в ключ неверно (например, дата сдвинута часовым поясом),
        // ключ не совпадёт с записанным, и строка не найдётся.
        try (PreparedQuery full = session.prepare("SELECT note FROM k WHERE d = $1 AND ts = $2 AND code = $3 AND n = $4")) {
            assertThat(rows(full.execute(List.of(Date.valueOf("2026-10-21"),
                    java.sql.Timestamp.valueOf("2026-10-21 09:30:00"), "b", 3L)))).containsExactly(List.of("three"));
            assertThat(rows(full.execute(List.of(Date.valueOf("2026-10-21"),
                    java.sql.Timestamp.valueOf("2026-10-21 09:30:01"), "b", 3L)))).isEmpty();
        }
        try (PreparedQuery prefix = session.prepare("SELECT note FROM k WHERE d = $1 ORDER BY n")) {
            assertThat(rows(prefix.execute(List.of(Date.valueOf("2026-10-21")))))
                    .containsExactly(List.of("two"), List.of("three"));
        }
        try (PreparedQuery range = session.prepare("SELECT note FROM k WHERE d >= $1 AND d < $2 ORDER BY n")) {
            assertThat(rows(range.execute(List.of(Date.valueOf("2026-10-21"), Date.valueOf("2026-10-22")))))
                    .containsExactly(List.of("two"), List.of("three"));
        }
        try (PreparedQuery byId = session.prepare("SELECT name FROM t WHERE id = $1 OR id = $2")) {
            // OR ключом не берётся — Calcite проверяет сам; результат тот же.
            assertThat(rows(byId.execute(List.of(1, 2)))).containsExactly(List.of("ann"), List.of("bob"));
        }
        try (PreparedQuery nullKey = session.prepare("SELECT name FROM t WHERE id = $1")) {
            assertThat(rows(nullKey.execute(Arrays.asList((Object) null)))).isEmpty();
        }
    }

    private static List<List<Object>> rows(StatementResult result) {
        assertThat(result).isInstanceOf(StatementResult.Rows.class);
        return ((StatementResult.Rows) result).rows();
    }

    private static void assertSqlState(Runnable action, String sqlState) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(SwdbException.class, e -> assertThat(e.sqlState()).isEqualTo(sqlState));
    }
}
