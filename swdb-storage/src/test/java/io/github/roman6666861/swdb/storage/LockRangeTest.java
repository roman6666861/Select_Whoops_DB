package io.github.roman6666861.swdb.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LockRangeTest {

    @Test
    void overlappingAndNestedRangesHaveCommonKeys() {
        LockRange range = new LockRange(bytes(10), bytes(20));
        assertOverlap(range, new LockRange(bytes(15), bytes(30)), true);
        assertOverlap(range, new LockRange(bytes(12), bytes(18)), true);
        assertOverlap(range, new LockRange(bytes(10), bytes(20)), true);
        assertOverlap(range, LockRange.point(bytes(10)), true);
        assertOverlap(range, LockRange.point(bytes(19)), true);
    }

    @Test
    void adjacentAndDisjointRangesDoNotOverlap() {
        LockRange range = new LockRange(bytes(10), bytes(20));
        assertOverlap(range, new LockRange(bytes(20), bytes(30)), false);
        assertOverlap(range, new LockRange(bytes(0), bytes(10)), false);
        assertOverlap(range, LockRange.point(bytes(20)), false);
        assertOverlap(range, LockRange.point(bytes(30)), false);
    }

    @Test
    void emptyRangeNeverOverlaps() {
        LockRange empty = new LockRange(bytes(15), bytes(15));
        assertThat(empty.isEmpty()).isTrue();
        assertOverlap(empty, new LockRange(bytes(10), bytes(20)), false);
        assertOverlap(empty, empty, false);
        assertOverlap(empty, LockRange.prefix(bytes()), false);
    }

    @Test
    void unboundedRangesIncludeAllRemainingKeys() {
        LockRange all = LockRange.prefix(bytes());
        assertThat(all.from()).isEmpty();
        assertThat(all.to()).isNull();
        assertOverlap(all, LockRange.point(bytes()), true);
        assertOverlap(all, LockRange.point(bytes(255)), true);
        assertOverlap(new LockRange(bytes(20), null), LockRange.point(bytes(20)), true);
        assertOverlap(new LockRange(bytes(20), null), new LockRange(bytes(10), bytes(20)), false);
    }

    @Test
    void byteComparisonIsUnsignedAndLexicographic() {
        assertOverlap(new LockRange(bytes(127), bytes(255)), LockRange.point(bytes(128)), true);
        assertOverlap(new LockRange(bytes(1), bytes(2)), LockRange.point(bytes(1, 255)), true);
        assertThatThrownBy(() -> new LockRange(bytes(255), bytes(128)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LockRange(null, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void pointDoesNotIncludeLongerKeysButPrefixDoes() {
        LockRange point = LockRange.point(bytes(10));
        assertOverlap(point, LockRange.point(bytes(10)), true);
        assertOverlap(point, LockRange.point(bytes(10, 0)), false);
        assertOverlap(point, LockRange.point(bytes(10, 255)), false);
        assertOverlap(LockRange.prefix(bytes(10)), LockRange.point(bytes(10, 255)), true);
        assertOverlap(LockRange.prefix(bytes(10)), LockRange.point(bytes(11)), false);
        assertOverlap(LockRange.point(bytes()), LockRange.point(bytes(0)), false);
        assertOverlap(LockRange.prefix(bytes(255)), LockRange.point(bytes(255, 255)), true);
    }

    @Test
    void boundariesCannotBeChangedThroughArrays() {
        byte[] from = bytes(10);
        byte[] to = bytes(20);
        LockRange range = new LockRange(from, to);
        from[0] = 30;
        to[0] = 0;
        range.from()[0] = 30;
        range.to()[0] = 0;
        assertThat(range.from()).containsExactly((byte) 10);
        assertThat(range.to()).containsExactly((byte) 20);
        assertOverlap(range, LockRange.point(bytes(15)), true);
    }

    private static void assertOverlap(LockRange left, LockRange right, boolean expected) {
        assertThat(left.overlaps(right)).isEqualTo(expected);
        assertThat(right.overlaps(left)).isEqualTo(expected);
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }
}
