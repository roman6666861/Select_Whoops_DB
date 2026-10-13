package io.github.roman6666861.swdb.catalog;

import java.util.List;

/**
 * Граница диапазона по первичному ключу для {@link TableStore#scan(KeyBound, KeyBound)}.
 * <p>
 * {@code values} — значения первых колонок первичного ключа, по порядку. Для ключа {@code (d, seq)}
 * граница может задавать только {@code d}: тогда она относится ко всем строкам с этим {@code d}.
 */
public record KeyBound(List<Object> values, boolean inclusive) {

    public KeyBound {
        values = List.copyOf(values);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("key bound must have at least one value");
        }
    }

    public static KeyBound inclusive(Object... values) {
        return new KeyBound(List.of(values), true);
    }

    public static KeyBound exclusive(Object... values) {
        return new KeyBound(List.of(values), false);
    }
}
