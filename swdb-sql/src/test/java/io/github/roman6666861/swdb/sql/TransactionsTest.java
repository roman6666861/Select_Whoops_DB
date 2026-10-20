package io.github.roman6666861.swdb.sql;

import io.github.roman6666861.swdb.catalog.Catalog;
import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
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
 * BEGIN / COMMIT / ROLLBACK. Две сессии одного движка — как два подключённых клиента.
 */
class TransactionsTest {

    @TempDir
    Path dir;

    private KvStore store;
    private SqlEngine engine;
    private SqlSession first;
    private SqlSession second;

    @BeforeEach
    void open() {
        store = KvStore.open(dir);
        engine = new SqlEngine(new Catalog(store));
        first = engine.openSession();
        second = engine.openSession();
        first.execute("CREATE TABLE t (id INT PRIMARY KEY, v INT)");
        first.execute("INSERT INTO t VALUES (1, 10)");
    }

    @AfterEach
    void close() {
        first.close();
        second.close();
        store.close();
    }

    @Test
    void changesBecomeVisibleToOthersAtCommit() {
        assertThat(first.execute("BEGIN")).isEqualTo(new StatementResult.Update("BEGIN", 0));
        assertThat(first.transactionStatus()).isEqualTo(TransactionStatus.IN_TRANSACTION);
        first.execute("INSERT INTO t VALUES (2, 20)");
        first.execute("UPDATE t SET v = 11 WHERE id = 1");

        assertThat(ids(first)).containsExactly(1, 2);
        assertThat(rows(second, "SELECT v FROM t")).as("not committed yet").containsExactly(List.of(10));

        assertThat(first.execute("COMMIT")).isEqualTo(new StatementResult.Update("COMMIT", 0));
        assertThat(first.transactionStatus()).isEqualTo(TransactionStatus.IDLE);
        assertThat(rows(second, "SELECT id, v FROM t")).containsExactly(List.of(1, 11), List.of(2, 20));
    }

    @Test
    void rollbackUndoesWholeTransaction() {
        first.execute("BEGIN");
        first.execute("INSERT INTO t VALUES (2, 20)");
        first.execute("UPDATE t SET v = 0");
        first.execute("DELETE FROM t WHERE id = 1");

        assertThat(first.execute("ROLLBACK")).isEqualTo(new StatementResult.Update("ROLLBACK", 0));

        assertThat(rows(first, "SELECT id, v FROM t")).containsExactly(List.of(1, 10));
    }

    @Test
    void transactionReadsItsSnapshot() {
        first.execute("BEGIN");
        assertThat(ids(first)).containsExactly(1);

        second.execute("INSERT INTO t VALUES (2, 20)"); // autocommit: зафиксировано сразу

        // REPEATABLE READ: внутри транзакции данные не меняются, пока она не закончится.
        assertThat(ids(first)).containsExactly(1);
        first.execute("COMMIT");
        assertThat(ids(first)).containsExactly(1, 2);
    }

    @Test
    void errorAbortsTransactionUntilRollback() {
        first.execute("BEGIN");
        first.execute("INSERT INTO t VALUES (2, 20)");

        assertSqlState(() -> first.execute("INSERT INTO t VALUES (1, 99)"), SqlStates.UNIQUE_VIOLATION);
        assertThat(first.transactionStatus()).isEqualTo(TransactionStatus.FAILED);
        assertSqlState(() -> first.execute("SELECT 1"), SqlStates.IN_FAILED_SQL_TRANSACTION);
        assertSqlState(() -> first.execute("INSERT INTO t VALUES (3, 30)"), SqlStates.IN_FAILED_SQL_TRANSACTION);

        // COMMIT сломанной транзакции ничего не фиксирует.
        assertThat(first.execute("COMMIT")).isEqualTo(new StatementResult.Update("ROLLBACK", 0));
        assertThat(first.transactionStatus()).isEqualTo(TransactionStatus.IDLE);
        assertThat(ids(second)).containsExactly(1);
    }

    @Test
    void concurrentUpdateOfSameRowIsSerializationFailure() throws Exception {
        first.execute("BEGIN");
        second.execute("BEGIN");
        first.execute("UPDATE t SET v = v + 1 WHERE id = 1");

        // second ждёт блокировку строки, которую держит first.
        CompletableFuture<StatementResult> waiting =
                CompletableFuture.supplyAsync(() -> second.execute("UPDATE t SET v = v + 1 WHERE id = 1"));
        Thread.sleep(200);
        assertThat(waiting).isNotDone();

        first.execute("COMMIT");

        // second видела v = 10, а строку уже изменили: записать 11 значило бы потерять обновление first.
        assertThat(waiting).failsWithin(5, TimeUnit.SECONDS).withThrowableThat()
                .havingCause().isInstanceOfSatisfying(SwdbException.class,
                        e -> assertThat(e.sqlState()).isEqualTo(SqlStates.SERIALIZATION_FAILURE));
        assertThat(second.transactionStatus()).isEqualTo(TransactionStatus.FAILED);
        second.execute("ROLLBACK");

        // Повтор транзакции видит новое значение.
        second.execute("BEGIN");
        second.execute("UPDATE t SET v = v + 1 WHERE id = 1");
        second.execute("COMMIT");
        assertThat(rows(first, "SELECT v FROM t")).containsExactly(List.of(12));
    }

    @Test
    void closingSessionRollsBack() {
        SqlSession third = engine.openSession();
        third.execute("BEGIN");
        third.execute("INSERT INTO t VALUES (2, 20)");

        third.close(); // клиент отключился посреди транзакции

        assertThat(ids(first)).containsExactly(1);
    }

    @Test
    void ddlIsNotAllowedInsideTransaction() {
        first.execute("BEGIN");

        assertSqlState(() -> first.execute("CREATE TABLE u (id INT PRIMARY KEY)"), SqlStates.FEATURE_NOT_SUPPORTED);
        assertThat(first.transactionStatus()).isEqualTo(TransactionStatus.FAILED);
        first.execute("ROLLBACK");

        first.execute("DROP TABLE t"); // вне транзакции DDL работает
    }

    @Test
    void postgresSyntaxVariants() {
        for (String[] block : new String[][]{
                {"START TRANSACTION", "END"},
                {"begin work", "commit transaction"},
                {"BEGIN ISOLATION LEVEL REPEATABLE READ;", "ABORT"},
                {"BEGIN TRANSACTION", "ROLLBACK WORK"}}) {
            first.execute(block[0]);
            assertThat(first.transactionStatus()).as(block[0]).isEqualTo(TransactionStatus.IN_TRANSACTION);
            first.execute(block[1]);
            assertThat(first.transactionStatus()).as(block[1]).isEqualTo(TransactionStatus.IDLE);
        }
        // Как в PostgreSQL: лишние BEGIN / COMMIT / ROLLBACK ничего не делают.
        assertThat(first.execute("COMMIT")).isEqualTo(new StatementResult.Update("COMMIT", 0));
        assertThat(first.execute("ROLLBACK")).isEqualTo(new StatementResult.Update("ROLLBACK", 0));
        first.execute("BEGIN");
        first.execute("BEGIN");
        assertThat(first.transactionStatus()).isEqualTo(TransactionStatus.IN_TRANSACTION);
        first.execute("ROLLBACK");

        assertSqlState(() -> first.execute("BEGIN ISOLATION LEVEL SERIALIZABLE"), SqlStates.FEATURE_NOT_SUPPORTED);
        assertThat(first.transactionStatus()).isEqualTo(TransactionStatus.IDLE);
    }

    private static List<Integer> ids(SqlSession session) {
        return rows(session, "SELECT id FROM t").stream().map(row -> (Integer) row.getFirst()).toList();
    }

    private static List<List<Object>> rows(SqlSession session, String sql) {
        StatementResult result = session.execute(sql);
        assertThat(result).isInstanceOf(StatementResult.Rows.class);
        return ((StatementResult.Rows) result).rows().stream().map(List::copyOf).toList();
    }

    private static void assertSqlState(Runnable action, String sqlState) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(SwdbException.class, e -> assertThat(e.sqlState()).isEqualTo(sqlState));
    }
}
