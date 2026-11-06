package io.github.roman6666861.swdb.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.function.Consumer;

import static io.github.roman6666861.swdb.storage.RangeLockManager.Mode.EXCLUSIVE;
import static io.github.roman6666861.swdb.storage.RangeLockManager.Mode.SHARED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionRangeLocksTest {

    private static final LockRange RANGE = LockRange.point(new byte[]{10});

    @TempDir
    Path dir;

    @Test
    void commitReleasesRangesBeforeClose() {
        assertReleasedBy(KvTransaction::commit);
    }

    @Test
    void rollbackReleasesRangesBeforeClose() {
        assertReleasedBy(KvTransaction::rollback);
    }

    private void assertReleasedBy(Consumer<KvTransaction> finish) {
        try (KvStore store = KvStore.open(dir);
             KvTransaction first = store.begin(); KvTransaction second = store.begin()) {
            assertThat(first.tryLockRange(RANGE, EXCLUSIVE)).isTrue();
            assertThat(second.tryLockRange(RANGE, SHARED)).isFalse();
            finish.accept(first);
            assertThat(second.tryLockRange(RANGE, EXCLUSIVE)).isTrue();
            assertThatThrownBy(() -> first.tryLockRange(RANGE, SHARED))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void closeRollsBackAndReleasesRanges() {
        try (KvStore store = KvStore.open(dir); KvTransaction second = store.begin()) {
            try (KvTransaction first = store.begin()) {
                assertThat(first.tryLockRange(RANGE, EXCLUSIVE)).isTrue();
                first.put(new byte[]{10}, new byte[]{1});
                assertThat(second.tryLockRange(RANGE, SHARED)).isFalse();
            }
            assertThat(second.tryLockRange(RANGE, EXCLUSIVE)).isTrue();
            assertThat(store.get(new byte[]{10})).isNull();
        }
    }

    @Test
    void releasingOneReaderDoesNotReleaseAnother() {
        try (KvStore store = KvStore.open(dir);
             KvTransaction first = store.begin(); KvTransaction second = store.begin();
             KvTransaction third = store.begin()) {
            assertThat(first.tryLockRange(RANGE, SHARED)).isTrue();
            assertThat(second.tryLockRange(RANGE, SHARED)).isTrue();
            first.commit();
            assertThat(third.tryLockRange(RANGE, EXCLUSIVE)).isFalse();
            second.rollback();
            assertThat(third.tryLockRange(RANGE, EXCLUSIVE)).isTrue();
        }
    }

    @Test
    void separateStoresHaveIndependentRangeLocks() {
        try (KvStore firstStore = KvStore.open(dir.resolve("first"));
             KvStore secondStore = KvStore.open(dir.resolve("second"));
             KvTransaction first = firstStore.begin(); KvTransaction second = secondStore.begin()) {
            assertThat(first.tryLockRange(RANGE, EXCLUSIVE)).isTrue();
            assertThat(second.tryLockRange(RANGE, EXCLUSIVE)).isTrue();
        }
    }

    @Test
    void rollbackToSavepointKeepsRangesUntilTransactionEnds() {
        try (KvStore store = KvStore.open(dir);
             KvTransaction first = store.begin(); KvTransaction second = store.begin()) {
            first.setSavepoint();
            assertThat(first.tryLockRange(RANGE, EXCLUSIVE)).isTrue();
            first.rollbackToSavepoint();
            assertThat(second.tryLockRange(RANGE, SHARED)).isFalse();
            first.rollback();
            assertThat(second.tryLockRange(RANGE, EXCLUSIVE)).isTrue();
        }
    }
}
