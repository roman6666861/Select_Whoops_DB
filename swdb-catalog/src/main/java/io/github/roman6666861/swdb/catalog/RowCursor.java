package io.github.roman6666861.swdb.catalog;

import io.github.roman6666861.swdb.storage.KvIterator;

/**
 * Курсор по строкам таблицы в порядке первичного ключа. Обязательно закрывать.
 */
public final class RowCursor implements AutoCloseable {

    private final KvIterator iterator;
    private final RowCodec codec;
    private Object[] current;

    RowCursor(KvIterator iterator, RowCodec codec) {
        this.iterator = iterator;
        this.codec = codec;
    }

    public boolean next() {
        if (iterator.next()) {
            current = codec.decodeValue(iterator.value());
            return true;
        }
        current = null;
        return false;
    }

    /** Текущая строка во внутреннем представлении Calcite. */
    public Object[] row() {
        return current;
    }

    @Override
    public void close() {
        iterator.close();
    }
}
