package io.github.roman6666861.swdb.storage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RangeWriteHistoryTest {

    private final RangeWriteHistory history = new RangeWriteHistory();
    private final RangeLockManager.Owner reader = new RangeLockManager.Owner();
    private final RangeLockManager.Owner writer = new RangeLockManager.Owner();

    @Test
    void onlyIntersectingWritesAfterSnapshotConflict() {
        history.register(reader, 100);
        history.register(writer, 100);
        history.committed(writer, 101, List.of(range(10, 20)));
        assertThat(history.hasConflict(reader, point(15))).isTrue();
        assertThat(history.hasConflict(reader, point(10))).isTrue();
        assertThat(history.hasConflict(reader, point(20))).isFalse();
        assertThat(history.hasConflict(reader, point(30))).isFalse();
        assertThat(history.hasConflict(reader, range(15, 15))).isFalse();
    }

    @Test
    void writeAtSnapshotSequenceDoesNotConflictWithThatReader() {
        RangeLockManager.Owner oldest = new RangeLockManager.Owner();
        history.register(oldest, 99);
        history.register(reader, 100);
        history.register(writer, 99);
        history.committed(writer, 100, List.of(point(15)));
        assertThat(history.retainedWriteCount()).isEqualTo(1);
        assertThat(history.hasConflict(reader, point(15))).isFalse();
        assertThat(history.hasConflict(oldest, point(15))).isTrue();
    }

    @Test
    void cleanupKeepsWritesNeededByOldestLiveSnapshot() {
        RangeLockManager.Owner newer = new RangeLockManager.Owner();
        history.register(reader, 100);
        history.register(newer, 110);
        history.register(writer, 100);
        history.committed(writer, 105, List.of(point(15)));
        assertThat(history.retainedWriteCount()).isEqualTo(1);
        assertThat(history.hasConflict(reader, point(15))).isTrue();
        assertThat(history.hasConflict(newer, point(15))).isFalse();
        history.unregister(reader);
        assertThat(history.retainedWriteCount()).isZero();
    }

    @Test
    void refreshingOldestSnapshotPrunesOnlyObsoleteWrites() {
        history.register(reader, 100);
        history.register(writer, 100);
        history.committed(writer, 105, List.of(point(15)));
        RangeLockManager.Owner nextWriter = new RangeLockManager.Owner();
        history.register(nextWriter, 105);
        history.committed(nextWriter, 110, List.of(point(25)));
        history.refreshSnapshot(reader, 105);
        assertThat(history.retainedWriteCount()).isEqualTo(1);
        assertThat(history.hasConflict(reader, point(15))).isFalse();
        assertThat(history.hasConflict(reader, point(25))).isTrue();
        history.refreshSnapshot(reader, 110);
        assertThat(history.retainedWriteCount()).isZero();
    }

    @Test
    void lastOwnerLeavingClearsHistoryAndRollbackAddsNothing() {
        history.register(reader, 100);
        history.register(writer, 100);
        history.committed(writer, 101, List.of(point(15)));
        history.unregister(reader);
        history.unregister(reader);
        assertThat(history.retainedWriteCount()).isZero();
        history.register(reader, 101);
        history.register(writer, 101);
        history.unregister(writer);
        assertThat(history.hasConflict(reader, point(15))).isFalse();
    }

    @Test
    void commitWithSeveralRangesIgnoresEmptyRangesAndSupportsNoWrites() {
        history.register(reader, 100);
        history.register(writer, 100);
        history.committed(writer, 101, List.of(point(15), range(20, 20), point(25)));
        assertThat(history.retainedWriteCount()).isEqualTo(2);
        assertThat(history.hasConflict(reader, point(15))).isTrue();
        assertThat(history.hasConflict(reader, point(25))).isTrue();
        history.register(writer, 101);
        history.committed(writer, 101, List.of());
        assertThat(history.retainedWriteCount()).isEqualTo(2);
    }

    @Test
    void commitPublicationOrderNeedNotMatchSequenceOrder() {
        history.register(reader, 100);
        history.register(writer, 100);
        RangeLockManager.Owner other = new RangeLockManager.Owner();
        history.register(other, 100);
        history.committed(writer, 110, List.of(point(15)));
        history.committed(other, 105, List.of(point(25)));
        assertThat(history.hasConflict(reader, point(15))).isTrue();
        assertThat(history.hasConflict(reader, point(25))).isTrue();
        history.refreshSnapshot(reader, 105);
        assertThat(history.retainedWriteCount()).isEqualTo(1);
        assertThat(history.hasConflict(reader, point(15))).isTrue();
    }

    @Test
    void invalidLifecycleAndSequencesAreRejectedWithoutChangingSnapshot() {
        assertThatThrownBy(() -> history.hasConflict(reader, point(15)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> history.register(reader, -1)).isInstanceOf(IllegalArgumentException.class);
        history.register(reader, 100);
        assertThatThrownBy(() -> history.register(reader, 200)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> history.refreshSnapshot(reader, 99)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> history.committed(reader, 99, List.of(point(15))))
                .isInstanceOf(IllegalArgumentException.class);
        history.register(writer, 100);
        history.committed(writer, 101, List.of(point(15)));
        assertThat(history.hasConflict(reader, point(15))).isTrue();
    }

    private static LockRange range(int from, int to) {
        return new LockRange(new byte[]{(byte) from}, new byte[]{(byte) to});
    }

    private static LockRange point(int key) {
        return LockRange.point(new byte[]{(byte) key});
    }
}
