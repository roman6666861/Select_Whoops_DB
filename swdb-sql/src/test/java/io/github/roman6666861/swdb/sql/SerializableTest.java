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
 * SERIALIZABLE: результат одновременных SERIALIZABLE-транзакций такой, будто они выполнялись
 * по очереди. Классический пример — дежурные врачи: правило «хотя бы один дежурит» каждая
 * транзакция проверяет сама, но вместе они могут его нарушить (write skew).
 */
class SerializableTest {

    @TempDir
    Path dir;

    private KvStore store;
    private SqlSession first;
    private SqlSession second;

    @BeforeEach
    void open() {
        store = KvStore.open(dir);
        SqlEngine engine = new SqlEngine(new Catalog(store));
        first = engine.openSession();
        second = engine.openSession();
        first.execute("CREATE TABLE doctors (id INT PRIMARY KEY, on_call BOOLEAN)");
        first.execute("INSERT INTO doctors VALUES (1, TRUE), (2, TRUE)");
    }

    @AfterEach
    void close() {
        first.close();
        second.close();
        store.close();
    }

    @Test
    void repeatableReadAllowsWriteSkew() {
        first.execute("BEGIN ISOLATION LEVEL REPEATABLE READ");
        second.execute("BEGIN ISOLATION LEVEL REPEATABLE READ");
        assertThat(onCall(first)).isEqualTo(2);
        assertThat(onCall(second)).isEqualTo(2);

        // Каждая видит двух дежурных и снимает «своего» — строки разные, конфликта записи нет.
        first.execute("UPDATE doctors SET on_call = FALSE WHERE id = 1");
        second.execute("UPDATE doctors SET on_call = FALSE WHERE id = 2");
        first.execute("COMMIT");
        second.execute("COMMIT");

        assertThat(onCall(first)).as("аномалия: не дежурит никто").isZero();
    }

    @Test
    void serializablePreventsWriteSkew() throws Exception {
        first.execute("BEGIN ISOLATION LEVEL SERIALIZABLE");
        second.execute("BEGIN ISOLATION LEVEL SERIALIZABLE");
        assertThat(onCall(first)).isEqualTo(2);
        assertThat(onCall(second)).isEqualTo(2);

        // Запись ждёт, пока второй читатель таблицы не закончится...
        CompletableFuture<StatementResult> firstUpdate =
                CompletableFuture.supplyAsync(() -> first.execute("UPDATE doctors SET on_call = FALSE WHERE id = 1"));
        Thread.sleep(300);
        assertThat(firstUpdate).isNotDone();

        // ...а второй ждал бы первого: круг, и одна из транзакций должна откатиться.
        assertThatThrownBy(() -> second.execute("UPDATE doctors SET on_call = FALSE WHERE id = 2"))
                .isInstanceOfSatisfying(SwdbException.class,
                        e -> assertThat(e.sqlState()).isEqualTo(SqlStates.SERIALIZATION_FAILURE));

        // Транзакция second уже откатана и её блокировки сняты: first продолжает, не дожидаясь ROLLBACK.
        firstUpdate.get(5, TimeUnit.SECONDS);
        // А блок в сессии second остаётся сломанным, пока клиент его не закроет.
        assertThat(second.transactionStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThatThrownBy(() -> onCall(second))
                .isInstanceOfSatisfying(SwdbException.class,
                        e -> assertThat(e.sqlState()).isEqualTo(SqlStates.IN_FAILED_SQL_TRANSACTION));
        second.execute("ROLLBACK");

        first.execute("COMMIT");
        assertThat(onCall(first)).isEqualTo(1);

        // Повтор второй транзакции видит одного дежурного и правило не нарушит.
        second.execute("BEGIN ISOLATION LEVEL SERIALIZABLE");
        assertThat(onCall(second)).isEqualTo(1);
        second.execute("ROLLBACK");
    }

    @Test
    void readAfterSerializableWriteCommittedAfterSnapshotFails() {
        first.execute("BEGIN ISOLATION LEVEL SERIALIZABLE");
        first.execute("SELECT 1"); // снимок уже взят, таблица ещё не читалась

        second.execute("BEGIN ISOLATION LEVEL SERIALIZABLE");
        second.execute("UPDATE doctors SET on_call = FALSE WHERE id = 1");
        second.execute("COMMIT");

        // По снимку first «раньше» second, но прочитать таблицу хочет уже «после» неё.
        assertThatThrownBy(() -> onCall(first))
                .isInstanceOfSatisfying(SwdbException.class,
                        e -> assertThat(e.sqlState()).isEqualTo(SqlStates.SERIALIZATION_FAILURE));
        first.execute("ROLLBACK");
    }

    @Test
    void writerWaitsForReaderAndThenSucceeds() throws Exception {
        first.execute("BEGIN ISOLATION LEVEL SERIALIZABLE");
        assertThat(onCall(first)).isEqualTo(2);

        second.execute("BEGIN ISOLATION LEVEL SERIALIZABLE");
        CompletableFuture<StatementResult> update =
                CompletableFuture.supplyAsync(() -> second.execute("UPDATE doctors SET on_call = FALSE WHERE id = 2"));
        Thread.sleep(300);
        assertThat(update).as("first ещё читает таблицу").isNotDone();

        first.execute("COMMIT");
        assertThat(update.get(5, TimeUnit.SECONDS)).isEqualTo(new StatementResult.Update("UPDATE", 1));
        second.execute("COMMIT");
    }

    @Test
    void otherLevelsAreNotBlockedBySerializable() throws Exception {
        first.execute("BEGIN ISOLATION LEVEL SERIALIZABLE");
        assertThat(onCall(first)).isEqualTo(2);

        // Гарантия только между SERIALIZABLE-транзакциями: обычная запись не ждёт.
        CompletableFuture<StatementResult> update =
                CompletableFuture.supplyAsync(() -> second.execute("UPDATE doctors SET on_call = FALSE WHERE id = 2"));
        assertThat(update.get(5, TimeUnit.SECONDS)).isEqualTo(new StatementResult.Update("UPDATE", 1));
        first.execute("COMMIT");
    }

    private static long onCall(SqlSession session) {
        List<List<Object>> rows = ((StatementResult.Rows) session.execute(
                "SELECT COUNT(*) FROM doctors WHERE on_call")).rows();
        return ((Number) rows.getFirst().getFirst()).longValue();
    }
}
