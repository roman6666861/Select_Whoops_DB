package io.github.roman6666861.swdb.sql.schema;

import io.github.roman6666861.swdb.catalog.KeyBound;

/**
 * Какую часть таблицы читать: диапазон первичного ключа для {@code TableStore.scan(lower, upper)}.
 * Граница {@code null} — без ограничения с этой стороны.
 */
record KeyRange(KeyBound lower, KeyBound upper) {

    /** Вся таблица. */
    static final KeyRange ALL = new KeyRange(null, null);
}
