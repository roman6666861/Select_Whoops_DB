package io.github.roman6666861.swdb.sql;

import io.github.roman6666861.swdb.catalog.Catalog;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DDL одновременно с работой других сессий: блокировки таблиц не дают DDL и DML перемешаться.
 * Вторая сессия выполняет оператор в другом потоке, пока первая держит блокировку.
 */
class DdlConcurrencyTest {

    @TempDir
    Path dir;

    private KvStore store;
    private Catalog catalog;
    private SqlSession first;
    private SqlSession second;

    @BeforeEach
    void open() {
        store = KvStore.open(dir);
        catalog = new Catalog(store);
        SqlEngine engine = new SqlEngine(catalog);
        first = engine.openSession();
        second = engine.openSession();
        first.execute("CREATE TABLE users (id INT PRIMARY KEY, email VARCHAR(20))");
        first.execute("INSERT INTO users VALUES (1, 'ann@x')");
    }

    @AfterEach
    void close() {
        first.close();
        second.close();
        store.close();
    }

    @Test
    void createIndexWaitsForWriterAndIndexesItsRows() throws Exception {
        first.execute("BEGIN");
        first.execute("INSERT INTO users VALUES (2, 'bob@x')");

        CompletableFuture<StatementResult> createIndex =
                CompletableFuture.supplyAsync(() -> second.execute("CREATE INDEX users_email ON users (email)"));
        Thread.sleep(300);
        assertThat(createIndex).as("ждёт, пока пишущая транзакция не закончится").isNotDone();

        first.execute("COMMIT");
        createIndex.get(5, TimeUnit.SECONDS);

        // Без блокировки индекс построился бы по снимку без строки 2.
        assertThat(rows(first, "SELECT id FROM users WHERE email = 'bob@x'")).containsExactly(List.of(2));
        assertThat(indexEntries()).isEqualTo(2);
    }

    @Test
    void readerDelaysDropIndex() throws Exception {
        first.execute("CREATE INDEX users_email ON users (email)");
        first.execute("BEGIN");
        assertThat(rows(first, "SELECT id FROM users WHERE email = 'ann@x'")).containsExactly(List.of(1));

        CompletableFuture<StatementResult> dropIndex =
                CompletableFuture.supplyAsync(() -> second.execute("DROP INDEX users_email"));
        Thread.sleep(300);
        assertThat(dropIndex).as("читающая транзакция ещё открыта").isNotDone();
        // Пока DDL ждёт, читатель продолжает работать с индексом.
        assertThat(rows(first, "SELECT id FROM users WHERE email = 'ann@x'")).containsExactly(List.of(1));

        first.execute("COMMIT");
        dropIndex.get(5, TimeUnit.SECONDS);
        assertThat(catalog.table("users").orElseThrow().table().indexes()).isEmpty();
    }

    @Test
    void writerWaitsForDropTableAndLeavesNoRows() throws Exception {
        int tableId = catalog.table("users").orElseThrow().table().id();

        // DROP TABLE в ещё не зафиксированной транзакции держит исключительную блокировку.
        second.execute("BEGIN");
        second.execute("DROP TABLE users");

        CompletableFuture<StatementResult> insert =
                CompletableFuture.supplyAsync(() -> first.execute("INSERT INTO users VALUES (3, 'c@x')"));
        Thread.sleep(300);
        assertThat(insert).as("ждёт DROP TABLE").isNotDone();

        second.execute("COMMIT");
        // Описание таблицы удалено после снимка INSERT — повторить оператор.
        assertThat(insert).failsWithin(5, TimeUnit.SECONDS).withThrowableThat()
                .havingCause().isInstanceOfSatisfying(SwdbException.class,
                        e -> assertThat(e.sqlState()).isEqualTo(SqlStates.SERIALIZATION_FAILURE));
        assertThatThrownBy(() -> first.execute("INSERT INTO users VALUES (3, 'c@x')"))
                .isInstanceOfSatisfying(SwdbException.class,
                        e -> assertThat(e.sqlState()).isEqualTo(SqlStates.UNDEFINED_TABLE));
        assertThat(keys(new KeyBuilder().appendByte('D').appendInt(tableId).build()))
                .as("строк-сирот нет").isZero();
    }

    @Test
    void transactionStartedBeforeDdlMustRetry() {
        first.execute("BEGIN");
        first.execute("SELECT 1");

        second.execute("CREATE INDEX users_email ON users (email)");

        // Снимок first старше индекса: по нему записей индекса нет, и читать по нему нельзя.
        assertThatThrownBy(() -> first.execute("SELECT id FROM users WHERE email = 'ann@x'"))
                .isInstanceOfSatisfying(SwdbException.class,
                        e -> assertThat(e.sqlState()).isEqualTo(SqlStates.SERIALIZATION_FAILURE));
        first.execute("ROLLBACK");
        assertThat(rows(first, "SELECT id FROM users WHERE email = 'ann@x'")).containsExactly(List.of(1));
    }

    private static List<List<Object>> rows(SqlSession session, String sql) {
        return ((StatementResult.Rows) session.execute(sql)).rows();
    }

    private int indexEntries() {
        int tableId = catalog.table("users").orElseThrow().table().id();
        return keys(new KeyBuilder().appendByte('I').appendInt(tableId).appendInt(1).build());
    }

    private int keys(byte[] prefix) {
        int count = 0;
        try (KvIterator it = store.scan(prefix)) {
            while (it.next()) {
                count++;
            }
        }
        return count;
    }
}
