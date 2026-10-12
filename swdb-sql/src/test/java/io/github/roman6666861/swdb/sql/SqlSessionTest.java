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
import java.sql.Timestamp;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlSessionTest {

    @TempDir
    Path dir;

    private KvStore store;
    private SqlEngine engine;
    private SqlSession session;

    @BeforeEach
    void open() {
        store = KvStore.open(dir);
        engine = new SqlEngine(new Catalog(store));
        session = engine.openSession();
    }

    @AfterEach
    void close() {
        session.close();
        store.close();
    }

    @Test
    void selectsConstantExpression() {
        StatementResult result = session.execute("SELECT 1 + 2 AS three;");

        assertThat(result).isInstanceOfSatisfying(StatementResult.Rows.class, rows -> {
            assertThat(rows.columns()).extracting(Column::name).containsExactly("three");
            assertThat(rows.rows()).containsExactly(List.of(3));
        });
    }

    @Test
    void emptyQuery() {
        assertThat(session.execute(" ; ")).isInstanceOf(StatementResult.Empty.class);
    }

    @Test
    void setStoresSessionSetting() {
        StatementResult result = session.execute("SET application_name = 'psql'");

        assertThat(result).isEqualTo(new StatementResult.Update("SET", 0));
        assertThat(session.setting("application_name")).contains("psql");
    }

    @Test
    void syntaxErrorHasSqlState() {
        assertSqlState(() -> session.execute("SELEKT 1"), SqlStates.SYNTAX_ERROR);
    }

    @Test
    void createInsertSelect() {
        assertThat(session.execute("CREATE TABLE users (id INT, name VARCHAR(20) NOT NULL, age INT, PRIMARY KEY (id))"))
                .isEqualTo(new StatementResult.Update("CREATE TABLE", 0));
        assertThat(session.execute("INSERT INTO users VALUES (2, 'bob', 25), (1, 'ann', NULL)"))
                .isEqualTo(new StatementResult.Update("INSERT", 2));
        session.execute("INSERT INTO users (id, name) VALUES (3, 'carol')");

        assertThat(rows("SELECT id, name, age FROM users")).containsExactly(
                List.of(1, "ann"),   // NULL в конце обрезан ниже
                List.of(2, "bob", 25),
                List.of(3, "carol"));
    }

    @Test
    void whereGroupByJoin() {
        session.execute("CREATE TABLE dept (id INT, title VARCHAR(20), PRIMARY KEY (id))");
        session.execute("CREATE TABLE emp (id INT, dept_id INT, salary INT, PRIMARY KEY (id))");
        session.execute("INSERT INTO dept VALUES (1, 'dev'), (2, 'ops')");
        session.execute("INSERT INTO emp VALUES (1, 1, 100), (2, 1, 200), (3, 2, 50), (4, 2, 70)");

        assertThat(rows("""
                SELECT d.title, SUM(e.salary) AS total
                FROM emp e JOIN dept d ON e.dept_id = d.id
                WHERE e.salary > 60
                GROUP BY d.title
                ORDER BY total DESC""")).containsExactly(List.of("dev", 300), List.of("ops", 70));
    }

    @Test
    void deleteWithWhere() {
        session.execute("CREATE TABLE t (id INT, v INT, PRIMARY KEY (id))");
        session.execute("INSERT INTO t VALUES (1, 10), (2, 20), (3, 30)");

        assertThat(session.execute("DELETE FROM t WHERE v >= 20")).isEqualTo(new StatementResult.Update("DELETE", 2));
        assertThat(rows("SELECT id FROM t")).containsExactly(List.of(1));
    }

    @Test
    void updateIsReportedAsNotSupported() {
        session.execute("CREATE TABLE t (id INT, v INT, PRIMARY KEY (id))");

        assertSqlState(() -> session.execute("UPDATE t SET v = 1"), SqlStates.FEATURE_NOT_SUPPORTED);
    }

    @Test
    void insertFromSelectOfSameTable() {
        session.execute("CREATE TABLE t (id INT, v INT, PRIMARY KEY (id))");
        session.execute("INSERT INTO t VALUES (1, 10), (2, 20)");

        // Скан видит снимок данных на момент начала, поэтому вставленные строки не читаются повторно.
        assertThat(session.execute("INSERT INTO t SELECT id + 10, v FROM t"))
                .isEqualTo(new StatementResult.Update("INSERT", 2));
        assertThat(rows("SELECT id FROM t")).containsExactly(List.of(1), List.of(2), List.of(11), List.of(12));
    }

    @Test
    void singleColumnTable() {
        session.execute("CREATE TABLE tags (name VARCHAR(10), PRIMARY KEY (name))");
        session.execute("INSERT INTO tags VALUES ('b'), ('a')");

        assertThat(rows("SELECT name FROM tags")).containsExactly(List.of("a"), List.of("b"));
        assertThat(session.execute("DELETE FROM tags WHERE name = 'a'")).isEqualTo(new StatementResult.Update("DELETE", 1));
    }

    @Test
    void compositePrimaryKeyAndAllTypes() {
        session.execute("""
                CREATE TABLE events (
                  d DATE, seq BIGINT, ts TIMESTAMP, ok BOOLEAN, score DOUBLE, price DECIMAL(10, 2), note TEXT,
                  PRIMARY KEY (d, seq))""");
        session.execute("""
                INSERT INTO events VALUES
                  (DATE '2026-10-11', 2, TIMESTAMP '2026-10-11 12:30:45.123', TRUE, 1.5, 9.99, 'hello'),
                  (DATE '2026-10-10', 7, TIMESTAMP '2026-10-10 00:00:00', FALSE, -2.0, 100, NULL)""");

        assertThat(rows("SELECT d, seq, ts, ok, score, price, note FROM events")).containsExactly(
                List.of(Date.valueOf("2026-10-10"), 7L, Timestamp.valueOf("2026-10-10 00:00:00"), false, -2.0,
                        new BigDecimal("100.00")),
                List.of(Date.valueOf("2026-10-11"), 2L, Timestamp.valueOf("2026-10-11 12:30:45.123"), true, 1.5,
                        new BigDecimal("9.99"), "hello"));
    }

    @Test
    void tableCreatedInOneSessionIsVisibleInAnother() {
        session.execute("CREATE TABLE t (id INT, PRIMARY KEY (id))");
        session.execute("INSERT INTO t VALUES (1)");

        try (SqlSession other = engine.openSession()) {
            StatementResult result = other.execute("SELECT id FROM t");
            assertThat(((StatementResult.Rows) result).rows()).containsExactly(List.of(1));
        }
    }

    @Test
    void dataSurvivesRestart() {
        session.execute("CREATE TABLE t (id INT, v VARCHAR(10), PRIMARY KEY (id))");
        session.execute("INSERT INTO t VALUES (1, 'kept')");
        session.close();
        store.close();

        store = KvStore.open(dir);
        engine = new SqlEngine(new Catalog(store));
        session = engine.openSession();

        assertThat(rows("SELECT v FROM t")).containsExactly(List.of("kept"));
    }

    @Test
    void dropTable() {
        session.execute("CREATE TABLE t (id INT, PRIMARY KEY (id))");
        assertThat(session.execute("DROP TABLE t")).isEqualTo(new StatementResult.Update("DROP TABLE", 0));

        assertSqlState(() -> session.execute("SELECT id FROM t"), SqlStates.UNDEFINED_TABLE);
        assertSqlState(() -> session.execute("DROP TABLE t"), SqlStates.UNDEFINED_TABLE);
        session.execute("DROP TABLE IF EXISTS t");
    }

    @Test
    void constraintViolations() {
        session.execute("CREATE TABLE t (id INT, name VARCHAR(3) NOT NULL, PRIMARY KEY (id))");
        session.execute("INSERT INTO t VALUES (1, 'a')");

        assertSqlState(() -> session.execute("INSERT INTO t VALUES (1, 'b')"), SqlStates.UNIQUE_VIOLATION);
        assertSqlState(() -> session.execute("CREATE TABLE t (id INT, PRIMARY KEY (id))"), SqlStates.DUPLICATE_TABLE);
        assertSqlState(() -> session.execute("SELECT nope FROM t"), SqlStates.UNDEFINED_COLUMN);
        assertSqlState(() -> session.execute("CREATE TABLE nokey (id INT)"), SqlStates.FEATURE_NOT_SUPPORTED);
    }

    /** Строки результата; NULL в конце строки отбрасываются, чтобы сравнивать через List.of. */
    private List<List<Object>> rows(String sql) {
        StatementResult result = session.execute(sql);
        assertThat(result).isInstanceOf(StatementResult.Rows.class);
        return ((StatementResult.Rows) result).rows().stream()
                .map(row -> {
                    int end = row.size();
                    while (end > 0 && row.get(end - 1) == null) {
                        end--;
                    }
                    return List.copyOf(row.subList(0, end));
                })
                .toList();
    }

    private static void assertSqlState(Runnable action, String sqlState) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(SwdbException.class, e -> assertThat(e.sqlState()).isEqualTo(sqlState));
    }
}
