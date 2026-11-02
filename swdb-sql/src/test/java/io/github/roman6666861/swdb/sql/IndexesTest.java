package io.github.roman6666861.swdb.sql;

import io.github.roman6666861.swdb.catalog.Catalog;
import io.github.roman6666861.swdb.catalog.IndexDef;
import io.github.roman6666861.swdb.catalog.TableDef;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CREATE INDEX и DROP INDEX через {@link SqlSession}.
 */
class IndexesTest {

    @TempDir
    Path dir;

    private KvStore store;
    private Catalog catalog;
    private SqlSession session;

    @BeforeEach
    void open() {
        store = KvStore.open(dir);
        catalog = new Catalog(store);
        session = new SqlEngine(catalog).openSession();
        session.execute("CREATE TABLE users (id INT PRIMARY KEY, email VARCHAR(20), age INT)");
        session.execute("INSERT INTO users VALUES (1, 'ann@x', 30), (2, 'bob@x', 40)");
    }

    @AfterEach
    void close() {
        session.close();
        store.close();
    }

    @Test
    void createIndexOnTableWithRows() {
        assertThat(session.execute("CREATE INDEX users_email ON users (email);"))
                .isEqualTo(new StatementResult.Update("CREATE INDEX", 0));

        assertThat(indexes()).containsExactly(new IndexDef(1, "users_email", List.of(1)));
        assertThat(entries("users_email")).isEqualTo(2);
    }

    @Test
    void dataChangesKeepIndexFilled() {
        session.execute("CREATE INDEX users_age_email ON users (age, email)");

        session.execute("INSERT INTO users VALUES (3, NULL, 50)");
        assertThat(entries("users_age_email")).isEqualTo(3);
        session.execute("UPDATE users SET age = age + 1");
        session.execute("UPDATE users SET id = id + 10");
        assertThat(entries("users_age_email")).isEqualTo(3);
        session.execute("DELETE FROM users WHERE id = 11");
        assertThat(entries("users_age_email")).isEqualTo(2);
    }

    @Test
    void dropIndex() {
        session.execute("CREATE INDEX users_email ON users (email)");

        assertThat(session.execute("DROP INDEX users_email")).isEqualTo(new StatementResult.Update("DROP INDEX", 0));

        assertThat(indexes()).isEmpty();
        assertThat(session.execute("DROP INDEX IF EXISTS users_email"))
                .isEqualTo(new StatementResult.Update("DROP INDEX", 0));
    }

    @Test
    void nameIsGeneratedLikePostgres() {
        session.execute("CREATE INDEX ON users (email)");
        session.execute("CREATE INDEX ON users (email)");
        session.execute("CREATE INDEX ON users (age, email)");

        assertThat(indexes()).extracting(IndexDef::name)
                .containsExactly("users_email_idx", "users_email_idx1", "users_age_email_idx");
    }

    @Test
    void preparedIndexCommandsRunOnExecute() {
        PreparedQuery create = session.prepare("CREATE INDEX users_email ON users (email)");
        assertThat(indexes()).as("not executed yet").isEmpty();

        create.execute(List.of());

        assertThat(indexes()).extracting(IndexDef::name).containsExactly("users_email");
    }

    @Test
    void decimalColumnCanBeIndexed() {
        session.execute("CREATE TABLE accounts (id INT PRIMARY KEY, balance DECIMAL(38, 2))");
        session.execute("INSERT INTO accounts VALUES (1, 10.5), (2, NULL)");

        session.execute("CREATE INDEX accounts_balance ON accounts (balance)");
        session.execute("UPDATE accounts SET balance = balance * 2");

        assertThat(rows("SELECT CAST(balance AS VARCHAR) FROM accounts WHERE id = 1")).containsExactly(List.of("21.00"));
    }

    @Test
    void statementPreparedBeforeCreateIndexMaintainsIt() {
        PreparedQuery insert = session.prepare("INSERT INTO users VALUES (3, 'c@x', 50)");
        PreparedQuery update = session.prepare("UPDATE users SET email = 'z@x' WHERE id = 1");
        PreparedQuery delete = session.prepare("DELETE FROM users WHERE id = 2");
        session.execute("CREATE INDEX users_email ON users (email)");

        // План подготовлен, когда индекса ещё не было, но пишет уже с ним.
        insert.execute(List.of());
        assertThat(entries("users_email")).isEqualTo(3);
        update.execute(List.of());
        delete.execute(List.of());
        assertThat(entries("users_email")).isEqualTo(2);
    }

    @Test
    void queriesThroughIndexReturnSameRowsAsFullScan() {
        session.execute("INSERT INTO users VALUES (3, 'carol@x', 30), (4, NULL, 30), (5, 'dan@x', NULL), "
                + "(6, 'eve@x', 25), (7, 'ann@x', 40)");
        List<String> queries = List.of(
                "SELECT id FROM users WHERE age = 30",
                "SELECT id FROM users WHERE age > 25",
                "SELECT id FROM users WHERE age < 31",
                "SELECT id FROM users WHERE age IS NULL",
                "SELECT id FROM users WHERE age BETWEEN 26 AND 40",
                "SELECT id FROM users WHERE age = 30 AND email = 'carol@x'",
                "SELECT id FROM users WHERE age = 30 AND email IS NULL",
                "SELECT id FROM users WHERE age = 40 AND email < 'b'",
                "SELECT id FROM users WHERE email = 'ann@x'",
                "SELECT id FROM users WHERE email > 'b'",
                "SELECT id FROM users WHERE age = 30 OR email = 'ann@x'");
        List<List<List<Object>>> withoutIndexes = queries.stream().map(q -> sorted(rows(q))).toList();

        session.execute("CREATE INDEX users_age ON users (age)");
        session.execute("CREATE INDEX users_age_email ON users (age, email)");
        session.execute("CREATE INDEX users_email ON users (email)");

        for (int i = 0; i < queries.size(); i++) {
            assertThat(sorted(rows(queries.get(i)))).as(queries.get(i)).isEqualTo(withoutIndexes.get(i));
        }
    }

    @Test
    void whereReallyReadsThroughIndex() {
        session.execute("CREATE INDEX users_email ON users (email)");
        // Уберём из индекса запись строки 2 в обход TableStore: если запрос идёт через индекс,
        // строки он не найдёт, а полный скан — нашёл бы.
        byte[] prefix = new KeyBuilder().appendByte('I').appendInt(table().id()).appendInt(1).build();
        try (KvTransaction tx = store.begin(); KvIterator it = tx.scan(prefix, KeyBuilder.prefixEnd(prefix))) {
            while (it.next()) {
                if (Arrays.equals(it.value(), dataKey(2))) {
                    tx.delete(it.key());
                }
            }
            tx.commit();
        }

        assertThat(rows("SELECT id FROM users WHERE email = 'bob@x'")).isEmpty();
        assertThat(rows("SELECT id FROM users WHERE id = 2")).as("по первичному ключу строка на месте").hasSize(1);
    }

    @Test
    void primaryKeyWinsTieWithIndex() {
        session.execute("CREATE INDEX users_age ON users (age)");

        // id = 1 и age = 30 — по одному равенству; выигрывает первичный ключ, оба условия верны.
        assertThat(rows("SELECT id FROM users WHERE id = 1 AND age = 30")).containsExactly(List.of(1));
        assertThat(rows("SELECT id FROM users WHERE id = 1 AND age = 40")).isEmpty();
    }

    @Test
    void preparedParametersUseIndex() {
        session.execute("CREATE INDEX users_age ON users (age)");

        try (PreparedQuery query = session.prepare("SELECT email FROM users WHERE age >= $1 ORDER BY age")) {
            assertThat(((StatementResult.Rows) query.execute(List.of(35))).rows()).containsExactly(List.of("bob@x"));
            assertThat(((StatementResult.Rows) query.execute(List.of(0))).rows())
                    .containsExactly(List.of("ann@x"), List.of("bob@x"));
        }
    }

    /**
     * «Halloween problem»: запрос читает через индекс и меняет ту же колонку. Если бы скан видел
     * собственные изменения, строка, переехавшая дальше по индексу, встретилась бы снова.
     */
    @Test
    void modificationsThroughIndexSeeEachRowOnce() {
        session.execute("CREATE INDEX users_age ON users (age)");

        assertThat(session.execute("UPDATE users SET age = age + 100 WHERE age >= 30"))
                .isEqualTo(new StatementResult.Update("UPDATE", 2));
        assertThat(rows("SELECT age FROM users ORDER BY id")).containsExactly(List.of(130), List.of(140));

        assertThat(session.execute("INSERT INTO users SELECT id + 10, email, age + 1 FROM users WHERE age > 100"))
                .isEqualTo(new StatementResult.Update("INSERT", 2));
        assertThat(rows("SELECT id FROM users WHERE age > 100")).hasSize(4);

        assertThat(session.execute("DELETE FROM users WHERE age > 135"))
                .isEqualTo(new StatementResult.Update("DELETE", 2));
        assertThat(rows("SELECT id, age FROM users ORDER BY id")).containsExactly(List.of(1, 130), List.of(11, 131));
        assertThat(entries("users_age")).isEqualTo(2);
    }

    @Test
    void uniqueIndex() {
        session.execute("CREATE UNIQUE INDEX users_email_key ON users (email)");
        assertThat(indexes()).containsExactly(new IndexDef(1, "users_email_key", List.of(1), true));

        assertThatThrownBy(() -> session.execute("INSERT INTO users VALUES (3, 'ann@x', 50)"))
                .isInstanceOfSatisfying(SwdbException.class, e -> {
                    assertThat(e.sqlState()).isEqualTo(SqlStates.UNIQUE_VIOLATION);
                    assertThat(e.getMessage())
                            .isEqualTo("duplicate key value violates unique constraint \"users_email_key\"");
                });
        assertSqlState("UPDATE users SET email = 'ann@x'", SqlStates.UNIQUE_VIOLATION);
        session.execute("INSERT INTO users VALUES (3, NULL, 50), (4, NULL, 60)");
        session.execute("UPDATE users SET email = CASE id WHEN 1 THEN 'bob@x' WHEN 2 THEN 'ann@x' END WHERE id <= 2");

        assertThat(rows("SELECT id FROM users WHERE email = 'ann@x'")).containsExactly(List.of(2));
        assertThat(rows("SELECT id FROM users WHERE email IS NULL ORDER BY id")).containsExactly(List.of(3), List.of(4));
    }

    @Test
    void uniqueIndexOnDuplicatesFails() {
        session.execute("INSERT INTO users VALUES (3, 'x@x', 30)");

        assertSqlState("CREATE UNIQUE INDEX users_age_key ON users (age)", SqlStates.UNIQUE_VIOLATION);
        assertThat(indexes()).isEmpty();
    }

    @Test
    void errors() {
        session.execute("CREATE INDEX users_email ON users (email)");

        assertSqlState("CREATE INDEX users_email ON users (age)", SqlStates.DUPLICATE_TABLE);
        assertSqlState("CREATE INDEX users ON users (age)", SqlStates.DUPLICATE_TABLE);
        assertThat(session.execute("CREATE INDEX IF NOT EXISTS users_email ON users (age)"))
                .isEqualTo(new StatementResult.Update("CREATE INDEX", 0));
        assertSqlState("CREATE INDEX i ON missing (age)", SqlStates.UNDEFINED_TABLE);
        assertSqlState("CREATE INDEX i ON users (missing)", SqlStates.UNDEFINED_COLUMN);
        assertSqlState("DROP INDEX missing", SqlStates.UNDEFINED_OBJECT);
        assertSqlState("CREATE INDEX i ON users", SqlStates.SYNTAX_ERROR);

        assertThat(indexes()).extracting(IndexDef::name).containsExactly("users_email");
    }

    @Test
    void rollbackUndoesIndexDdl() {
        session.execute("CREATE INDEX users_email ON users (email)");
        session.execute("BEGIN");
        session.execute("CREATE INDEX users_age ON users (age)");
        session.execute("DROP INDEX users_email");
        assertThat(rows("SELECT id FROM users WHERE age = 30")).containsExactly(List.of(1));

        session.execute("ROLLBACK");

        assertThat(indexes()).extracting(IndexDef::name).containsExactly("users_email");
        assertThat(entries("users_email")).isEqualTo(2);
    }

    private List<List<Object>> rows(String sql) {
        return ((StatementResult.Rows) session.execute(sql)).rows();
    }

    /** Строки в порядке первой колонки: без ORDER BY порядок зависит от того, через что читали. */
    private static List<List<Object>> sorted(List<List<Object>> rows) {
        return rows.stream().sorted(Comparator.comparing(row -> (Integer) row.getFirst())).toList();
    }

    /** Ключ строки {@code users}: {@code 'D' [tableId] [id]}. */
    private byte[] dataKey(int id) {
        return new KeyBuilder().appendByte('D').appendInt(table().id()).appendInt(id).build();
    }

    private List<IndexDef> indexes() {
        return table().indexes();
    }

    private TableDef table() {
        return catalog.table("users").orElseThrow().table();
    }

    /** Число записей индекса в хранилище: ключи {@code 'I' [tableId] [indexId] ...}. */
    private int entries(String index) {
        int indexId = indexes().stream().filter(i -> i.name().equals(index)).findFirst().orElseThrow().id();
        int count = 0;
        try (KvIterator it = store.scan(new KeyBuilder().appendByte('I').appendInt(table().id()).appendInt(indexId).build())) {
            while (it.next()) {
                count++;
            }
        }
        return count;
    }

    private void assertSqlState(String sql, String sqlState) {
        assertThatThrownBy(() -> session.execute(sql))
                .as(sql)
                .isInstanceOfSatisfying(SwdbException.class, e -> assertThat(e.sqlState()).isEqualTo(sqlState));
    }
}
