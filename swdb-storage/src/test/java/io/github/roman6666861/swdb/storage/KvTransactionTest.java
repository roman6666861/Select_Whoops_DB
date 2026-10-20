package io.github.roman6666861.swdb.storage;

import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Изоляция снимка: свои изменения видны сразу, чужие — только в транзакциях, начатых после COMMIT;
 * запись чужого изменённого ключа — конфликт.
 */
class KvTransactionTest {

    @TempDir
    Path dir;

    private KvStore store;

    @BeforeEach
    void open() {
        store = KvStore.open(dir);
    }

    @AfterEach
    void close() {
        store.close();
    }

    @Test
    void changesAreVisibleToOthersOnlyAfterCommit() {
        try (KvTransaction tx = store.begin()) {
            tx.put(bytes("k"), bytes("v"));

            assertThat(tx.get(bytes("k"))).isEqualTo(bytes("v"));
            assertThat(store.get(bytes("k"))).isNull();

            tx.commit();
        }
        assertThat(store.get(bytes("k"))).isEqualTo(bytes("v"));
    }

    @Test
    void rollbackAndCloseDiscardChanges() {
        store.put(bytes("k"), bytes("old"));
        try (KvTransaction tx = store.begin()) {
            tx.put(bytes("k"), bytes("new"));
            tx.rollback();
        }
        try (KvTransaction tx = store.begin()) {
            tx.delete(bytes("k"));
            // close без commit — тоже откат
        }
        assertThat(store.get(bytes("k"))).isEqualTo(bytes("old"));
    }

    @Test
    void readsSeeSnapshotTakenAtBegin() {
        store.put(bytes("a"), bytes("1"));
        try (KvTransaction tx = store.begin()) {
            try (KvTransaction other = store.begin()) {
                other.put(bytes("a"), bytes("2"));
                other.put(bytes("b"), bytes("2"));
                other.commit();
            }

            assertThat(tx.get(bytes("a"))).isEqualTo(bytes("1"));
            assertThat(keys(tx.scan(bytes("a"), null))).containsExactly("a");
        }
        try (KvTransaction later = store.begin()) {
            assertThat(later.get(bytes("a"))).isEqualTo(bytes("2"));
        }
    }

    @Test
    void scanMergesOwnChangesWithSnapshot() {
        store.put(bytes("a"), bytes("1"));
        store.put(bytes("c"), bytes("1"));
        try (KvTransaction tx = store.begin()) {
            tx.put(bytes("b"), bytes("1"));
            tx.delete(bytes("c"));
            tx.put(bytes("z"), bytes("1"));

            assertThat(keys(tx.scan(bytes("a"), bytes("d")))).containsExactly("a", "b");
        }
    }

    @Test
    void writingKeyChangedAfterSnapshotIsConflict() {
        store.put(bytes("k"), bytes("1"));
        try (KvTransaction tx = store.begin()) {
            try (KvTransaction other = store.begin()) {
                other.put(bytes("k"), bytes("2"));
                other.commit();
            }

            // Иначе tx затёрла бы значение 2, которого не видела.
            assertSqlState(() -> tx.put(bytes("k"), bytes("3")), SqlStates.SERIALIZATION_FAILURE);
            assertSqlState(() -> tx.getForUpdate(bytes("k")), SqlStates.SERIALIZATION_FAILURE);
        }
    }

    @Test
    void lockedKeyTimesOut() {
        try (KvTransaction first = store.begin(); KvTransaction second = store.begin(Duration.ofMillis(100))) {
            first.put(bytes("k"), bytes("1"));

            assertSqlState(() -> second.put(bytes("k"), bytes("2")), SqlStates.LOCK_NOT_AVAILABLE);
        }
    }

    @Test
    void waiterContinuesWhenLockIsReleased() throws Exception {
        try (KvTransaction first = store.begin(); KvTransaction second = store.begin()) {
            first.put(bytes("k"), bytes("1"));
            CompletableFuture<Void> secondPut = CompletableFuture.runAsync(() -> second.put(bytes("k"), bytes("2")));
            Thread.sleep(200);
            assertThat(secondPut).as("second waits for the lock").isNotDone();

            first.rollback(); // first ничего не записала — конфликта нет

            secondPut.get(5, TimeUnit.SECONDS);
            second.commit();
        }
        assertThat(store.get(bytes("k"))).isEqualTo(bytes("2"));
    }

    @Test
    void uniqueKeyCheckWaitsForOtherInsert() throws Exception {
        try (KvTransaction first = store.begin()) {
            assertThat(first.getForUpdate(bytes("id5"))).isNull();
            first.put(bytes("id5"), bytes("first"));

            // Вторая начинает после первой, но до её COMMIT: ключ заблокирован, хотя его ещё нет в базе.
            try (KvTransaction second = store.begin()) {
                CompletableFuture<byte[]> check = CompletableFuture.supplyAsync(() -> second.getForUpdate(bytes("id5")));
                Thread.sleep(200);
                assertThat(check).isNotDone();

                first.commit();

                // Ключ записан после снимка второй транзакции — конфликт, а не «ключ свободен».
                assertThat(check).failsWithin(5, TimeUnit.SECONDS).withThrowableThat()
                        .havingRootCause().isInstanceOf(org.rocksdb.RocksDBException.class);
            }
        }
    }

    @Test
    void deadlockIsDetected() throws Exception {
        try (KvTransaction first = store.begin(); KvTransaction second = store.begin()) {
            first.put(bytes("a"), bytes("1"));
            second.put(bytes("b"), bytes("2"));
            CompletableFuture<Void> firstWaits = CompletableFuture.runAsync(() -> first.put(bytes("b"), bytes("1")));
            Thread.sleep(200);

            // first ждёт b у second, а second просит a у first — круг; second получает ошибку сразу.
            assertSqlState(() -> second.put(bytes("a"), bytes("2")), SqlStates.DEADLOCK_DETECTED);

            second.rollback();
            firstWaits.get(5, TimeUnit.SECONDS);
            first.commit();
        }
        assertThat(store.get(bytes("a"))).isEqualTo(bytes("1"));
        assertThat(store.get(bytes("b"))).isEqualTo(bytes("1"));
    }

    private static void assertSqlState(Runnable action, String sqlState) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(SwdbException.class, e -> assertThat(e.sqlState()).isEqualTo(sqlState));
    }

    private static List<String> keys(KvIterator iterator) {
        List<String> keys = new ArrayList<>();
        try (iterator) {
            while (iterator.next()) {
                keys.add(new String(iterator.key(), UTF_8));
            }
        }
        return keys;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(UTF_8);
    }
}
