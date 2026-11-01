package io.github.roman6666861.swdb.sql.schema;

import io.github.roman6666861.swdb.catalog.IndexDef;
import io.github.roman6666861.swdb.catalog.KeyBound;

/**
 * Какую часть таблицы читать: диапазон первичного ключа для {@code TableStore.scan(lower, upper)}
 * или, если задан {@code index}, диапазон этого индекса для {@code TableStore.scanIndex}.
 * Граница {@code null} — без ограничения с этой стороны.
 */
record KeyRange(IndexDef index, KeyBound lower, KeyBound upper) {

    /** Вся таблица. */
    static final KeyRange ALL = new KeyRange(null, null);

    /** Диапазон первичного ключа. */
    KeyRange(KeyBound lower, KeyBound upper) {
        this(null, lower, upper);
    }
}
