package io.github.roman6666861.swdb.storage;

import org.junit.jupiter.api.Test;

import static io.github.roman6666861.swdb.storage.RangeLockManager.Mode.EXCLUSIVE;
import static io.github.roman6666861.swdb.storage.RangeLockManager.Mode.SHARED;
import static org.assertj.core.api.Assertions.assertThat;

class RangeLockManagerTest {

    private final RangeLockManager manager = new RangeLockManager();
    private final RangeLockManager.Owner first = new RangeLockManager.Owner();
    private final RangeLockManager.Owner second = new RangeLockManager.Owner();
    private final RangeLockManager.Owner third = new RangeLockManager.Owner();

    @Test
    void overlappingReadersAreCompatibleButBlockWriter() {
        assertThat(manager.tryAcquire(first, range(10, 20), SHARED)).isTrue();
        assertThat(manager.tryAcquire(second, range(15, 25), SHARED)).isTrue();
        assertThat(manager.tryAcquire(third, range(18, 19), EXCLUSIVE)).isFalse();
    }

    @Test
    void writerBlocksOverlappingReadersAndWriters() {
        assertThat(manager.tryAcquire(first, range(10, 20), EXCLUSIVE)).isTrue();
        assertThat(manager.tryAcquire(second, range(15, 25), SHARED)).isFalse();
        assertThat(manager.tryAcquire(second, range(15, 25), EXCLUSIVE)).isFalse();
    }

    @Test
    void disjointAndAdjacentWritesAreIndependent() {
        assertThat(manager.tryAcquire(first, range(10, 20), EXCLUSIVE)).isTrue();
        assertThat(manager.tryAcquire(second, range(20, 30), EXCLUSIVE)).isTrue();
        assertThat(manager.tryAcquire(third, range(40, 50), EXCLUSIVE)).isTrue();
    }

    @Test
    void ownerCanReacquireAndUpgradeItsOwnRange() {
        assertThat(manager.tryAcquire(first, range(10, 20), SHARED)).isTrue();
        assertThat(manager.tryAcquire(first, range(10, 20), SHARED)).isTrue();
        assertThat(manager.tryAcquire(first, range(15, 25), EXCLUSIVE)).isTrue();
        assertThat(manager.tryAcquire(second, range(20, 25), SHARED)).isFalse();
        manager.releaseAll(first);
        assertThat(manager.tryAcquire(second, range(10, 25), EXCLUSIVE)).isTrue();
    }

    @Test
    void failedUpgradePreservesReadLockAndDoesNotRegisterWrite() {
        assertThat(manager.tryAcquire(first, range(10, 20), SHARED)).isTrue();
        assertThat(manager.tryAcquire(second, range(10, 20), SHARED)).isTrue();
        assertThat(manager.tryAcquire(first, range(10, 30), EXCLUSIVE)).isFalse();
        // Неудачный запрос не захватил даже свободную часть диапазона.
        assertThat(manager.tryAcquire(third, range(20, 30), EXCLUSIVE)).isTrue();
        manager.releaseAll(second);
        // Исходное чтение first по-прежнему защищено.
        assertThat(manager.tryAcquire(second, range(10, 20), EXCLUSIVE)).isFalse();
        manager.releaseAll(third);
        assertThat(manager.tryAcquire(first, range(10, 30), EXCLUSIVE)).isTrue();
    }

    @Test
    void releaseRemovesAllOwnerRangesAndPreservesOtherOwners() {
        assertThat(manager.tryAcquire(first, range(10, 20), SHARED)).isTrue();
        assertThat(manager.tryAcquire(first, range(30, 40), EXCLUSIVE)).isTrue();
        assertThat(manager.tryAcquire(second, range(10, 20), SHARED)).isTrue();
        manager.releaseAll(first);
        manager.releaseAll(first);
        assertThat(manager.tryAcquire(third, range(30, 40), EXCLUSIVE)).isTrue();
        assertThat(manager.tryAcquire(third, range(10, 20), EXCLUSIVE)).isFalse();
        manager.releaseAll(second);
        assertThat(manager.tryAcquire(third, range(10, 20), EXCLUSIVE)).isTrue();
    }

    @Test
    void emptyRangeDoesNotAcquireOrConflict() {
        assertThat(manager.tryAcquire(first, range(10, 30), EXCLUSIVE)).isTrue();
        assertThat(manager.tryAcquire(second, range(20, 20), EXCLUSIVE)).isTrue();
        manager.releaseAll(first);
        assertThat(manager.tryAcquire(third, range(10, 30), EXCLUSIVE)).isTrue();
    }

    private static LockRange range(int from, int to) {
        return new LockRange(new KeyBuilder().appendInt(from).build(), new KeyBuilder().appendInt(to).build());
    }
}
