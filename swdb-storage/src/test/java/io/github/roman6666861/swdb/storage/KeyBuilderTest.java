package io.github.roman6666861.swdb.storage;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Главное свойство кодирования: порядок закодированных ключей совпадает с порядком значений.
 */
class KeyBuilderTest {

    private static final Comparator<byte[]> UNSIGNED_BYTES = Arrays::compareUnsigned;

    @Test
    void intsKeepOrder() {
        assertKeepsOrder(List.of(Integer.MIN_VALUE, -1000, -1, 0, 1, 255, 256, 1000, Integer.MAX_VALUE),
                v -> new KeyBuilder().appendInt(v).build());
    }

    @Test
    void longsKeepOrder() {
        assertKeepsOrder(List.of(Long.MIN_VALUE, -5_000_000_000L, -1L, 0L, 1L, 5_000_000_000L, Long.MAX_VALUE),
                v -> new KeyBuilder().appendLong(v).build());
    }

    @Test
    void doublesKeepOrder() {
        assertKeepsOrder(List.of(Double.NEGATIVE_INFINITY, -1e300, -1.5, -0.0, 0.0, 1e-300, 1.5, 1e300,
                        Double.POSITIVE_INFINITY),
                v -> new KeyBuilder().appendDouble(v).build());
    }

    @Test
    void stringsKeepOrder() {
        assertKeepsOrder(List.of("", "\0", "\0a", "a", "a\0", "ab", "abc", "b", "я"),
                v -> new KeyBuilder().appendString(v).build());
    }

    @Test
    void compositeKeyComparesComponentByComponent() {
        // ("a", 2) < ("ab", 1): более короткая строка меньше, даже если за ней идёт большее число.
        byte[] a2 = new KeyBuilder().appendString("a").appendInt(2).build();
        byte[] ab1 = new KeyBuilder().appendString("ab").appendInt(1).build();
        assertThat(Arrays.compareUnsigned(a2, ab1)).isNegative();
    }

    @Test
    void prefixEndIncrementsLastByteThatIsNotFF() {
        assertThat(KeyBuilder.prefixEnd(new byte[]{0x61, 0x62})).containsExactly(0x61, 0x63);
        assertThat(KeyBuilder.prefixEnd(new byte[]{0x61, (byte) 0xFF})).containsExactly(0x62);
        assertThat(KeyBuilder.prefixEnd(new byte[]{0x61, 0x7F})).containsExactly(0x61, 0x80);
        assertThat(KeyBuilder.prefixEnd(new byte[]{(byte) 0xFF, (byte) 0xFF})).isNull();
    }

    @Test
    void prefixEndIsGreaterThanEveryKeyWithPrefix() {
        byte[] prefix = new KeyBuilder().appendInt(7).build();
        byte[] end = KeyBuilder.prefixEnd(prefix);

        assertThat(Arrays.compareUnsigned(new KeyBuilder().appendInt(7).appendString("zzz").build(), end)).isNegative();
        assertThat(Arrays.compareUnsigned(new KeyBuilder().appendInt(8).build(), end)).isNotNegative();
    }

    /** {@code sortedValues} уже отсортированы; их ключи должны идти в том же порядке. */
    private static <T> void assertKeepsOrder(List<T> sortedValues, Function<T, byte[]> encode) {
        List<byte[]> keys = sortedValues.stream().map(encode).toList();
        assertThat(keys).isSortedAccordingTo(UNSIGNED_BYTES);
        assertThat(keys.stream().map(Arrays::toString).distinct()).hasSameSizeAs(keys);
    }
}
