package io.github.roman6666861.swdb.sql;

import io.github.roman6666861.swdb.catalog.Catalog;
import io.github.roman6666861.swdb.catalog.IndexDef;
import io.github.roman6666861.swdb.catalog.TableDef;
import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
import io.github.roman6666861.swdb.storage.KeyBuilder;
import io.github.roman6666861.swdb.storage.KvIterator;
import io.github.roman6666861.swdb.storage.KvStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
    void errors() {
        session.execute("CREATE INDEX users_email ON users (email)");

        assertSqlState("CREATE INDEX users_email ON users (age)", SqlStates.DUPLICATE_TABLE);
        assertSqlState("CREATE INDEX users ON users (age)", SqlStates.DUPLICATE_TABLE);
        assertThat(session.execute("CREATE INDEX IF NOT EXISTS users_email ON users (age)"))
                .isEqualTo(new StatementResult.Update("CREATE INDEX", 0));
        assertSqlState("CREATE INDEX i ON missing (age)", SqlStates.UNDEFINED_TABLE);
        assertSqlState("CREATE INDEX i ON users (missing)", SqlStates.UNDEFINED_COLUMN);
        assertSqlState("CREATE UNIQUE INDEX i ON users (age)", SqlStates.FEATURE_NOT_SUPPORTED);
        assertSqlState("DROP INDEX missing", SqlStates.UNDEFINED_OBJECT);
        assertSqlState("CREATE INDEX i ON users", SqlStates.SYNTAX_ERROR);

        assertThat(indexes()).extracting(IndexDef::name).containsExactly("users_email");
    }

    @Test
    void indexDdlIsRejectedInsideTransactionBlock() {
        session.execute("BEGIN");

        assertSqlState("CREATE INDEX i ON users (age)", SqlStates.FEATURE_NOT_SUPPORTED);

        session.execute("ROLLBACK");
        assertThat(indexes()).isEmpty();
    }

    private List<List<Object>> rows(String sql) {
        return ((StatementResult.Rows) session.execute(sql)).rows();
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
