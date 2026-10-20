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
                ORDER BY total DESC""")).containsExactly(List.of("dev", 300L), List.of("ops", 70L));
    }

    @Test
    void deleteWithWhere() {
        session.execute("CREATE TABLE t (id INT, v INT, PRIMARY KEY (id))");
        session.execute("INSERT INTO t VALUES (1, 10), (2, 20), (3, 30)");

        assertThat(session.execute("DELETE FROM t WHERE v >= 20")).isEqualTo(new StatementResult.Update("DELETE", 2));
        assertThat(rows("SELECT id FROM t")).containsExactly(List.of(1));
    }

    @Test
    void updateValues() {
        session.execute("CREATE TABLE t (id INT, v INT, note VARCHAR(5), d DATE, PRIMARY KEY (id))");
        session.execute("INSERT INTO t VALUES (1, 10, 'a', DATE '2026-01-01'), (2, 20, 'b', NULL), (3, 30, 'c', NULL)");

        assertThat(session.execute("UPDATE t SET v = v + 1 WHERE id = 2")).isEqualTo(new StatementResult.Update("UPDATE", 1));
        assertThat(session.execute("UPDATE t SET d = d + INTERVAL '1' DAY, note = UPPER(note) WHERE id < 2"))
                .isEqualTo(new StatementResult.Update("UPDATE", 1));
        assertThat(session.execute("UPDATE t SET note = 'x' WHERE v > 25")).isEqualTo(new StatementResult.Update("UPDATE", 1));
        assertThat(session.execute("UPDATE t SET v = 0 WHERE id = 99")).isEqualTo(new StatementResult.Update("UPDATE", 0));

        assertThat(rows("SELECT * FROM t")).containsExactly(
                List.of(1, 10, "A", Date.valueOf("2026-01-02")),
                List.of(2, 21, "b"),
                List.of(3, 30, "x"));
        // Без WHERE меняются все строки; NULL в колонке, допускающей NULL.
        assertThat(session.execute("UPDATE t SET d = NULL")).isEqualTo(new StatementResult.Update("UPDATE", 3));
        assertThat(rows("SELECT id FROM t WHERE d IS NULL")).hasSize(3);
    }

    @Test
    void updatePrimaryKey() {
        session.execute("CREATE TABLE t (id INT, v INT NOT NULL, PRIMARY KEY (id))");
        session.execute("INSERT INTO t VALUES (1, 10), (2, 20), (3, 30)");

        // Новый ключ строки 1 — старый ключ строки 2: проверяется итоговое состояние, а не каждая строка.
        assertThat(session.execute("UPDATE t SET id = id + 1")).isEqualTo(new StatementResult.Update("UPDATE", 3));
        assertThat(rows("SELECT id, v FROM t")).containsExactly(List.of(2, 10), List.of(3, 20), List.of(4, 30));

        // Ошибка в любой строке отменяет весь UPDATE.
        assertSqlState(() -> session.execute("UPDATE t SET id = 4 WHERE id = 2"), SqlStates.UNIQUE_VIOLATION);
        assertSqlState(() -> session.execute("UPDATE t SET id = 9 WHERE id > 2"), SqlStates.UNIQUE_VIOLATION);
        assertSqlState(() -> session.execute("UPDATE t SET v = NULL WHERE id = 4"), SqlStates.NOT_NULL_VIOLATION);
        assertThat(rows("SELECT id, v FROM t")).containsExactly(List.of(2, 10), List.of(3, 20), List.of(4, 30));
    }

    @Test
    void updateSingleColumnTable() {
        session.execute("CREATE TABLE tags (name VARCHAR(10), PRIMARY KEY (name))");
        session.execute("INSERT INTO tags VALUES ('a'), ('b')");

        assertThat(session.execute("UPDATE tags SET name = 'c' WHERE name = 'a'")).isEqualTo(new StatementResult.Update("UPDATE", 1));
        assertThat(rows("SELECT name FROM tags")).containsExactly(List.of("b"), List.of("c"));
    }

    @Test
    void mergeIsReportedAsNotSupported() {
        session.execute("CREATE TABLE t (id INT, v INT, PRIMARY KEY (id))");

        assertSqlState(() -> session.execute("""
                MERGE INTO t USING (VALUES (1, 2)) AS s (id, v) ON t.id = s.id
                WHEN MATCHED THEN UPDATE SET v = s.v"""), SqlStates.FEATURE_NOT_SUPPORTED);
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
    void primaryKeyInColumnDeclaration() {
        session.execute("CREATE TABLE t (id INT PRIMARY KEY, price DECIMAL(10, 2), name VARCHAR(10))");
        session.execute("INSERT INTO t VALUES (2, 1.5, 'b'), (1, 2.5, 'a')");

        assertThat(rows("SELECT id, name FROM t")).containsExactly(List.of(1, "a"), List.of(2, "b"));
        assertSqlState(() -> session.execute("INSERT INTO t VALUES (1, 0, 'x')"), SqlStates.UNIQUE_VIOLATION);
        // Колонка ключа становится NOT NULL, как и при PRIMARY KEY (id).
        assertSqlState(() -> session.execute("INSERT INTO t SELECT NULL, 0, 'x' FROM (VALUES (1))"),
                SqlStates.NOT_NULL_VIOLATION);

        assertSqlState(() -> session.execute("CREATE TABLE two (a INT PRIMARY KEY, b INT PRIMARY KEY)"),
                SqlStates.INVALID_TABLE_DEFINITION);
        assertSqlState(() -> session.execute("CREATE TABLE both_ways (a INT PRIMARY KEY, b INT, PRIMARY KEY (b))"),
                SqlStates.INVALID_TABLE_DEFINITION);
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

    @Test
    void whereByPrimaryKey() {
        session.execute("CREATE TABLE t (id INT, name VARCHAR(5), v INT, PRIMARY KEY (id))");
        session.execute("INSERT INTO t VALUES (1, 'a', 10), (2, 'b', 20), (3, 'c', 30)");

        assertThat(rows("SELECT name FROM t WHERE id = 2")).containsExactly(List.of("b"));
        assertThat(rows("SELECT name FROM t WHERE 2 = id")).containsExactly(List.of("b"));
        assertThat(rows("SELECT name FROM t WHERE id = 4")).isEmpty();
        // Условие не по ключу остаётся Calcite и проверяется для найденной строки.
        assertThat(rows("SELECT name FROM t WHERE id = 2 AND v = 20")).containsExactly(List.of("b"));
        assertThat(rows("SELECT name FROM t WHERE id = 2 AND v = 99")).isEmpty();
        // Значения, которых не может быть в колонке: Calcite оборачивает колонку в CAST, ключ не используется.
        assertThat(rows("SELECT name FROM t WHERE id = 2.5")).isEmpty();
        assertThat(rows("SELECT name FROM t WHERE id = 3000000000")).isEmpty();

        assertThat(session.execute("DELETE FROM t WHERE id = 2")).isEqualTo(new StatementResult.Update("DELETE", 1));
        assertThat(rows("SELECT id FROM t")).containsExactly(List.of(1), List.of(3));
    }

    @Test
    void whereByCompositeAndStringPrimaryKey() {
        session.execute("CREATE TABLE events (d DATE, seq INT, note VARCHAR(10), PRIMARY KEY (d, seq))");
        session.execute("""
                INSERT INTO events VALUES
                  (DATE '2026-10-10', 1, 'a'), (DATE '2026-10-10', 2, 'b'), (DATE '2026-10-11', 1, 'c')""");
        session.execute("CREATE TABLE tags (name VARCHAR(5), PRIMARY KEY (name))");
        session.execute("INSERT INTO tags VALUES ('x'), ('y')");

        assertThat(rows("SELECT note FROM events WHERE seq = 1 AND d = DATE '2026-10-11'")).containsExactly(List.of("c"));
        // Только первая колонка составного ключа: читаются все строки с этим d.
        assertThat(rows("SELECT note FROM events WHERE d = DATE '2026-10-10'")).containsExactly(List.of("a"), List.of("b"));
        assertThat(rows("SELECT name FROM tags WHERE name = 'y'")).containsExactly(List.of("y"));
        assertThat(rows("SELECT name FROM tags WHERE name = 'too long'")).isEmpty();
    }

    @Test
    void whereByPrimaryKeyRange() {
        session.execute("CREATE TABLE t (id INT, v INT, PRIMARY KEY (id))");
        session.execute("INSERT INTO t VALUES (-3, 0), (1, 10), (2, 20), (3, 30), (4, 40), (10, 100)");

        assertThat(ids("SELECT id FROM t WHERE id > 2")).containsExactly(3, 4, 10);
        assertThat(ids("SELECT id FROM t WHERE id >= 2")).containsExactly(2, 3, 4, 10);
        assertThat(ids("SELECT id FROM t WHERE id < 2")).containsExactly(-3, 1);
        assertThat(ids("SELECT id FROM t WHERE 2 >= id")).containsExactly(-3, 1, 2);
        assertThat(ids("SELECT id FROM t WHERE id BETWEEN 2 AND 4")).containsExactly(2, 3, 4);
        assertThat(ids("SELECT id FROM t WHERE id > 1 AND id < 4")).containsExactly(2, 3);
        assertThat(ids("SELECT id FROM t WHERE id > 1 AND id < 4 AND v <> 20")).containsExactly(3);
        assertThat(ids("SELECT id FROM t WHERE id > 4 AND id < 2")).isEmpty();
        // IN — несколько точек, читается вся таблица; результат тот же.
        assertThat(ids("SELECT id FROM t WHERE id IN (1, 10)")).containsExactly(1, 10);

        assertThat(session.execute("DELETE FROM t WHERE id BETWEEN 2 AND 3")).isEqualTo(new StatementResult.Update("DELETE", 2));
        assertThat(ids("SELECT id FROM t")).containsExactly(-3, 1, 4, 10);
    }

    @Test
    void whereByCompositeKeyPrefixAndRange() {
        session.execute("CREATE TABLE events (d DATE, seq INT, PRIMARY KEY (d, seq))");
        session.execute("""
                INSERT INTO events VALUES
                  (DATE '2026-10-09', 9), (DATE '2026-10-10', 1), (DATE '2026-10-10', 2),
                  (DATE '2026-10-10', 3), (DATE '2026-10-11', 1)""");

        assertThat(ids("SELECT seq FROM events WHERE d = DATE '2026-10-10' AND seq >= 2")).containsExactly(2, 3);
        assertThat(ids("SELECT seq FROM events WHERE d = DATE '2026-10-10' AND seq < 3")).containsExactly(1, 2);
        assertThat(ids("SELECT seq FROM events WHERE d > DATE '2026-10-09'")).containsExactly(1, 2, 3, 1);
        assertThat(ids("SELECT seq FROM events WHERE d BETWEEN DATE '2026-10-09' AND DATE '2026-10-10' AND seq = 1"))
                .containsExactly(1);
        assertThat(ids("SELECT seq FROM events WHERE seq = 1")).containsExactly(1, 1);
    }

    @Test
    void varcharKeyRangeIsCheckedByCalcite() {
        session.execute("CREATE TABLE tags (name VARCHAR(5), PRIMARY KEY (name))");
        session.execute("INSERT INTO tags VALUES ('a'), ('ab'), ('abc'), ('b')");

        assertThat(rows("SELECT name FROM tags WHERE name > 'ab' AND name <= 'b'")).containsExactly(List.of("abc"), List.of("b"));
    }

    @Test
    void unicodeStrings() {
        // VARCHAR(4): длина считается в символах, а не в байтах ('ёжик' в UTF-8 — 8 байт).
        session.execute("CREATE TABLE words (w VARCHAR(4), lang VARCHAR(5), PRIMARY KEY (w))");
        session.execute("INSERT INTO words VALUES ('ёжик', 'ru'), ('日本', 'ja'), ('😀', 'emoji'), ('cat', 'en')");

        assertThat(rows("SELECT lang FROM words WHERE w = 'ёжик'")).containsExactly(List.of("ru"));
        assertThat(rows("SELECT w FROM words WHERE lang = 'ja'")).containsExactly(List.of("日本"));
        assertThat(rows("SELECT w FROM words WHERE lang = 'emoji'")).containsExactly(List.of("😀"));
        assertThat(rows("SELECT w FROM words WHERE w LIKE 'ё%'")).containsExactly(List.of("ёжик"));
        assertThat(rows("SELECT UPPER(w) FROM words WHERE lang = 'ru'")).containsExactly(List.of("ЁЖИК"));
    }

    @Test
    void tooLongStringIsRejectedNotTruncated() {
        session.execute("CREATE TABLE t (id INT, name VARCHAR(3), PRIMARY KEY (id))");
        session.execute("INSERT INTO t VALUES (1, 'abc')");

        assertSqlState(() -> session.execute("INSERT INTO t VALUES (2, 'abcd')"), SqlStates.STRING_DATA_RIGHT_TRUNCATION);
        assertSqlState(() -> session.execute("INSERT INTO t SELECT 2, name || 'x' FROM t"), SqlStates.STRING_DATA_RIGHT_TRUNCATION);
        assertSqlState(() -> session.execute("UPDATE t SET name = 'abcd'"), SqlStates.STRING_DATA_RIGHT_TRUNCATION);
        assertThat(rows("SELECT id, name FROM t")).containsExactly(List.of(1, "abc"));

        // Явный CAST обрезает, как и в PostgreSQL.
        session.execute("INSERT INTO t VALUES (2, CAST('abcdef' AS VARCHAR(3)))");
        // Длина — в символах Unicode: эмодзи один символ, хотя в Java это два char.
        session.execute("INSERT INTO t VALUES (3, '😀😀😀')");
        assertThat(rows("SELECT name FROM t WHERE id > 1")).containsExactly(List.of("abc"), List.of("😀😀😀"));
    }

    @Test
    void numbersAreRoundedHalfAwayFromZero() {
        session.execute("CREATE TABLE t (id INT, i INT, price DECIMAL(5, 2), PRIMARY KEY (id))");
        session.execute("INSERT INTO t VALUES (1, 2.7, 1.005), (2, -2.5, 2.999), (3, 2.4, -1.005)");

        assertThat(rows("SELECT i, price FROM t")).containsExactly(
                List.of(3, new BigDecimal("1.01")),
                List.of(-3, new BigDecimal("3.00")),
                List.of(2, new BigDecimal("-1.01")));
        assertThat(rows("SELECT CAST(2.5 AS INT), CAST(-2.7 AS INT)")).containsExactly(List.of(3, -3));
    }

    @Test
    void numericErrorsHaveSqlState() {
        session.execute("CREATE TABLE t (id INT, i INT, p DECIMAL(5, 2), PRIMARY KEY (id))");
        session.execute("INSERT INTO t VALUES (1, 1, 1)");

        assertSqlState(() -> session.execute("INSERT INTO t VALUES (2, 1, 12345.6)"), SqlStates.NUMERIC_VALUE_OUT_OF_RANGE);
        assertSqlState(() -> session.execute("INSERT INTO t VALUES (2, 3000000000, 1)"), SqlStates.NUMERIC_VALUE_OUT_OF_RANGE);
        assertSqlState(() -> session.execute("UPDATE t SET p = p * 100000"), SqlStates.NUMERIC_VALUE_OUT_OF_RANGE);
        assertSqlState(() -> session.execute("SELECT CAST(12345.6 AS DECIMAL(5, 2))"), SqlStates.NUMERIC_VALUE_OUT_OF_RANGE);
        // Константа вычисляется при загрузке сгенерированного класса: ошибка приходит как ExceptionInInitializerError.
        assertSqlState(() -> session.execute("SELECT 1 / 0"), SqlStates.DIVISION_BY_ZERO);
        assertSqlState(() -> session.execute("SELECT i / 0 FROM t"), SqlStates.DIVISION_BY_ZERO);
        assertSqlState(() -> session.execute("SELECT p / 0 FROM t"), SqlStates.DIVISION_BY_ZERO);
        assertSqlState(() -> session.execute("SELECT MOD(i, 0) FROM t"), SqlStates.DIVISION_BY_ZERO);

        assertThat(rows("SELECT id, i, p FROM t")).containsExactly(List.of(1, 1, new BigDecimal("1.00")));
    }

    @Test
    void integerOverflowIsAnError() {
        session.execute("CREATE TABLE t (id INT, i INT, b BIGINT, PRIMARY KEY (id))");
        session.execute("INSERT INTO t VALUES (1, 2147483647, 9223372036854775807)");

        for (String sql : List.of("SELECT i + 1 FROM t", "SELECT i * 2 FROM t", "SELECT -i - 2 FROM t",
                "SELECT 2147483647 + 1", "UPDATE t SET i = i + 1")) {
            assertThatThrownBy(() -> session.execute(sql))
                    .isInstanceOfSatisfying(SwdbException.class, e -> {
                        assertThat(e.sqlState()).isEqualTo(SqlStates.NUMERIC_VALUE_OUT_OF_RANGE);
                        assertThat(e.getMessage()).isEqualTo("integer out of range");
                    });
        }
        assertThatThrownBy(() -> session.execute("SELECT b + 1 FROM t"))
                .isInstanceOfSatisfying(SwdbException.class, e -> assertThat(e.getMessage()).isEqualTo("bigint out of range"));

        // В пределах типа — как раньше; результат не помещается в INT, но помещается в BIGINT.
        assertThat(rows("SELECT i - 1, CAST(i AS BIGINT) + 1 FROM t")).containsExactly(List.of(2147483646, 2147483648L));
        assertThat(rows("SELECT i FROM t")).containsExactly(List.of(2147483647));
    }

    @Test
    void sumOfIntegersIsBigint() {
        session.execute("CREATE TABLE t (id INT, i INT, PRIMARY KEY (id))");
        session.execute("INSERT INTO t VALUES (1, 2147483647), (2, 2)");

        // Сумма не помещается в INT, но SUM(int) имеет тип BIGINT, как в PostgreSQL.
        assertThat(rows("SELECT SUM(i) FROM t")).containsExactly(List.of(2147483649L));
        assertThat(rows("SELECT SUM(i) OVER (ORDER BY id) FROM t")).containsExactly(List.of(2147483647L), List.of(2147483649L));
        StatementResult result = session.execute("SELECT SUM(i) FROM t");
        assertThat(((StatementResult.Rows) result).columns().getFirst().jdbcType()).isEqualTo(java.sql.Types.BIGINT);
        assertThat(rows("SELECT AVG(i) FROM t")).containsExactly(List.of(new BigDecimal("1073741824.5000000000000000")));
    }

    @Test
    void averageIsNumericWithFraction() {
        session.execute("CREATE TABLE t (id INT, i INT, p DECIMAL(5, 2), d DOUBLE, g INT, PRIMARY KEY (id))");
        session.execute("INSERT INTO t VALUES (1, 1, 1.25, 1.0, 1), (2, 2, 2.50, 2.0, 1), (3, 4, 3.00, 4.0, 2)");

        // Как в PostgreSQL: среднее целых и DECIMAL — numeric с 16 знаками после запятой.
        assertThat(rows("SELECT AVG(i), AVG(p) FROM t WHERE id < 3"))
                .containsExactly(List.of(new BigDecimal("1.5000000000000000"), new BigDecimal("1.8750000000000000")));
        assertThat(rows("SELECT g, AVG(i) FROM t GROUP BY g ORDER BY g")).containsExactly(
                List.of(1, new BigDecimal("1.5000000000000000")), List.of(2, new BigDecimal("4.0000000000000000")));
        assertThat(rows("SELECT AVG(i) OVER (ORDER BY id) FROM t WHERE id < 3"))
                .containsExactly(List.of(new BigDecimal("1.0000000000000000")), List.of(new BigDecimal("1.5000000000000000")));
        // DOUBLE остаётся DOUBLE.
        assertThat(rows("SELECT AVG(d) FROM t WHERE id < 3")).containsExactly(List.of(1.5));
        assertThat(rows("SELECT AVG(i) FROM t WHERE id > 5")).containsExactly(List.of());
    }

    @Test
    void explainShowsPlanLineByLine() {
        session.execute("CREATE TABLE t (id INT, v INT, PRIMARY KEY (id))");

        for (String sql : List.of("EXPLAIN SELECT v FROM t WHERE id = 5", "EXPLAIN PLAN FOR SELECT v FROM t WHERE id = 5")) {
            StatementResult result = session.execute(sql);

            assertThat(result).isInstanceOfSatisfying(StatementResult.Rows.class, rows -> {
                assertThat(rows.columns()).extracting(Column::name).containsExactly("QUERY PLAN");
                assertThat(rows.rows()).hasSizeGreaterThan(1);
                // Условие передано в таблицу: она сама решит, какие строки читать.
                assertThat(rows.rows().getLast().getFirst()).asString()
                        .contains("BindableTableScan(table=[[public, t]], filters=[[=($0, 5)]])");
            });
        }
    }

    @Test
    void explainDoesNotExecuteStatement() {
        session.execute("CREATE TABLE t (id INT, PRIMARY KEY (id))");
        session.execute("INSERT INTO t VALUES (1)");

        session.execute("EXPLAIN DELETE FROM t");
        assertThat(rows("EXPLAIN UPDATE t SET id = 2").getFirst().getFirst()).asString()
                .startsWith("EnumerableTableUpdate(table=[[public, t]], operation=[UPDATE], updateColumnList=[[id]]");

        assertThat(rows("SELECT id FROM t")).containsExactly(List.of(1));
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

    /** Первая колонка результата — целые числа. */
    private List<Integer> ids(String sql) {
        return rows(sql).stream().map(row -> (Integer) row.getFirst()).toList();
    }

    private static void assertSqlState(Runnable action, String sqlState) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(SwdbException.class, e -> assertThat(e.sqlState()).isEqualTo(sqlState));
    }
}
