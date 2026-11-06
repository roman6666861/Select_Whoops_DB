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
    void repeatableReadSeesSnapshotOfBegin() {
        first.execute("BEGIN ISOLATION LEVEL REPEATABLE READ");
        assertThat(ids(first)).containsExactly(1);

        second.execute("INSERT INTO t VALUES (2, 20)"); // autocommit: зафиксировано сразу

        // REPEATABLE READ: внутри транзакции данные не меняются, пока она не закончится.
        assertThat(ids(first)).containsExactly(1);
        first.execute("COMMIT");
        assertThat(ids(first)).containsExactly(1, 2);
    }

    @Test
    void readCommittedStatementSeesEarlierCommits() {
        first.execute("BEGIN"); // READ COMMITTED по умолчанию, как в PostgreSQL
        first.execute("INSERT INTO t VALUES (3, 30)");
        assertThat(ids(first)).containsExactly(1, 3);

        second.execute("INSERT INTO t VALUES (2, 20)");

        // Новый оператор — новый снимок: видна строка second и своя незафиксированная строка.
        assertThat(ids(first)).containsExactly(1, 2, 3);
        first.execute("COMMIT");
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
    void readCommittedWaitsForConcurrentUpdateAndSeesIt() throws Exception {
        first.execute("BEGIN");
        second.execute("BEGIN");
        second.execute("INSERT INTO t VALUES (5, 50)");
        first.execute("UPDATE t SET v = v + 1 WHERE id = 1");

        CompletableFuture<StatementResult> waiting =
                CompletableFuture.supplyAsync(() -> second.execute("UPDATE t SET v = v + 1 WHERE id = 1"));
        Thread.sleep(200);
        assertThat(waiting).isNotDone();

        first.execute("COMMIT");

        // Оператор откатился к своей точке сохранения и выполнился заново со свежим снимком;
        // транзакция цела, и строка 5, вставленная до него, на месте.
        assertThat(waiting.get(5, TimeUnit.SECONDS)).isEqualTo(new StatementResult.Update("UPDATE", 1));
        assertThat(second.transactionStatus()).isEqualTo(TransactionStatus.IN_TRANSACTION);
        second.execute("COMMIT");
        assertThat(rows(first, "SELECT id, v FROM t ORDER BY id")).containsExactly(List.of(1, 12), List.of(5, 50));
    }

    @Test
    void repeatableReadConcurrentUpdateOfSameRowIsSerializationFailure() throws Exception {
        first.execute("BEGIN");
        second.execute("BEGIN ISOLATION LEVEL REPEATABLE READ");
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
    void statementOutsideBlockIsRetriedAfterConcurrentUpdate() throws Exception {
        first.execute("BEGIN");
        first.execute("UPDATE t SET v = v + 1 WHERE id = 1");

        // Вне BEGIN: ждёт строку, после COMMIT first получает 40001 и выполняется заново.
        CompletableFuture<StatementResult> waiting =
                CompletableFuture.supplyAsync(() -> second.execute("UPDATE t SET v = v + 1 WHERE id = 1"));
        Thread.sleep(200);
        assertThat(waiting).isNotDone();

        first.execute("COMMIT");

        assertThat(waiting.get(5, TimeUnit.SECONDS)).isEqualTo(new StatementResult.Update("UPDATE", 1));
        assertThat(rows(first, "SELECT v FROM t")).as("оба прибавления на месте").containsExactly(List.of(12));
    }

    @Test
    void concurrentInsertOfSameKeyOutsideBlockIsUniqueViolation() throws Exception {
        first.execute("BEGIN");
        first.execute("INSERT INTO t VALUES (2, 20)");

        CompletableFuture<StatementResult> waiting =
                CompletableFuture.supplyAsync(() -> second.execute("INSERT INTO t VALUES (2, 99)"));
        Thread.sleep(200);
        first.execute("COMMIT");

        // Повтор видит строку first: ошибка про ключ, а не «повторите транзакцию».
        assertThat(waiting).failsWithin(5, TimeUnit.SECONDS).withThrowableThat()
                .havingCause().isInstanceOfSatisfying(SwdbException.class,
                        e -> assertThat(e.sqlState()).isEqualTo(SqlStates.UNIQUE_VIOLATION));
    }

    @Test
    void rollbackUndoesCreateTable() {
        first.execute("BEGIN");
        assertThat(first.execute("CREATE TABLE u (id INT PRIMARY KEY, v INT)"))
                .isEqualTo(new StatementResult.Update("CREATE TABLE", 0));
        first.execute("INSERT INTO u VALUES (1, 10)");
        assertThat(rows(first, "SELECT v FROM u")).containsExactly(List.of(10));

        first.execute("ROLLBACK");

        assertSqlState(() -> first.execute("SELECT v FROM u"), SqlStates.UNDEFINED_TABLE);
        first.execute("CREATE TABLE u (id INT PRIMARY KEY)"); // имя снова свободно
    }

    @Test
    void committedCreateTableKeepsRows() {
        first.execute("BEGIN");
        first.execute("CREATE TABLE u (id INT PRIMARY KEY, v INT)");
        first.execute("INSERT INTO u VALUES (1, 10)");
        first.execute("COMMIT");

        assertThat(rows(second, "SELECT v FROM u")).containsExactly(List.of(10));
    }

    @Test
    void uncommittedTableIsInvisibleAndItsNameIsLocked() throws Exception {
        first.execute("BEGIN");
        first.execute("CREATE TABLE u (id INT PRIMARY KEY)");

        assertSqlState(() -> second.execute("SELECT id FROM u"), SqlStates.UNDEFINED_TABLE);
        CompletableFuture<StatementResult> sameName =
                CompletableFuture.supplyAsync(() -> second.execute("CREATE TABLE u (id INT PRIMARY KEY)"));
        Thread.sleep(300);
        assertThat(sameName).as("ждёт, чем закончится первая транзакция").isNotDone();

        first.execute("ROLLBACK");
        // Первая не создала таблицу — имя свободно, вторая её создаёт.
        assertThat(sameName.get(5, TimeUnit.SECONDS)).isEqualTo(new StatementResult.Update("CREATE TABLE", 0));
    }

    @Test
    void indexCreatedInTransactionContainsItsOwnRows() {
        first.execute("BEGIN");
        first.execute("INSERT INTO t VALUES (2, 20)");
        first.execute("CREATE INDEX t_v ON t (v)");
        first.execute("INSERT INTO t VALUES (3, 30)");

        // Строки 1 (зафиксирована раньше), 2 (до индекса) и 3 (после) — все в индексе.
        assertThat(rows(first, "SELECT id FROM t WHERE v >= 10 ORDER BY id"))
                .containsExactly(List.of(1), List.of(2), List.of(3));
        first.execute("COMMIT");
        assertThat(rows(second, "SELECT id FROM t WHERE v = 20")).containsExactly(List.of(2));
    }

    @Test
    void rollbackRestoresDroppedTable() {
        first.execute("CREATE INDEX t_v ON t (v)");
        first.execute("BEGIN");
        first.execute("DROP TABLE t");
        assertSqlState(() -> first.execute("SELECT id FROM t"), SqlStates.UNDEFINED_TABLE);
        first.execute("ROLLBACK"); // ошибка сломала транзакцию, ROLLBACK завершает её

        assertThat(ids(first)).containsExactly(1);
        assertThat(rows(first, "SELECT id FROM t WHERE v = 10")).containsExactly(List.of(1));
    }

    @Test
    void failedDdlBreaksTransaction() {
        first.execute("BEGIN");
        first.execute("INSERT INTO t VALUES (2, 10)");

        assertSqlState(() -> first.execute("CREATE UNIQUE INDEX t_v ON t (v)"), SqlStates.UNIQUE_VIOLATION);
        assertThat(first.transactionStatus()).isEqualTo(TransactionStatus.FAILED);
        assertSqlState(() -> first.execute("SELECT id FROM t"), SqlStates.IN_FAILED_SQL_TRANSACTION);
        first.execute("ROLLBACK");

        assertThat(ids(first)).as("INSERT до ошибки тоже откатился").containsExactly(1);
    }

    @Test
    void postgresSyntaxVariants() {
        for (String[] block : new String[][]{
                {"START TRANSACTION", "END"},
                {"begin work", "commit transaction"},
                {"BEGIN ISOLATION LEVEL REPEATABLE READ;", "ABORT"},
                {"BEGIN ISOLATION LEVEL READ COMMITTED", "COMMIT"},
                {"start transaction isolation level read  uncommitted", "end"},
                {"BEGIN ISOLATION LEVEL SERIALIZABLE", "COMMIT"},
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

        assertSqlState(() -> first.execute("BEGIN READ ONLY"), SqlStates.FEATURE_NOT_SUPPORTED);
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
