package io.github.roman6666861.swdb.catalog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Граница диапазона по первичному ключу ({@link TableStore#scan}) или по индексу
 * ({@link TableStore#scanIndex}).
 * <p>
 * {@code values} — значения первых колонок ключа или индекса, по порядку. Для ключа {@code (d, seq)}
 * граница может задавать только {@code d}: тогда она относится ко всем строкам с этим {@code d}.
 * <p>
 * В границе индекса значение может быть {@code null}: в индексе NULL лежат раньше всех значений
 * колонки, поэтому {@code inclusive(null)} с обеих сторон — это {@code IS NULL}, а нижняя граница
 * {@code exclusive(null)} отсекает NULL ({@code age < 30} не должно их возвращать).
 * В первичном ключе NULL не бывает.
 */
public record KeyBound(List<Object> values, boolean inclusive) {

    public KeyBound {
        // List.copyOf не принимает null, а в границе индекса он допустим.
        values = Collections.unmodifiableList(new ArrayList<>(values));
        if (values.isEmpty()) {
            throw new IllegalArgumentException("key bound must have at least one value");
        }
    }

    public static KeyBound inclusive(Object... values) {
        return new KeyBound(Arrays.asList(values), true);
    }

    public static KeyBound exclusive(Object... values) {
        return new KeyBound(Arrays.asList(values), false);
    }
}
