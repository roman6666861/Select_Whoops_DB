package io.github.roman6666861.swdb.catalog;

import io.github.roman6666861.swdb.storage.KvIterator;

import java.util.function.UnaryOperator;

/**
 * Курсор по строкам таблицы: в порядке первичного ключа или, при чтении через индекс,
 * в порядке индекса. Обязательно закрывать.
 */
public final class RowCursor implements AutoCloseable {

    private final KvIterator iterator;
    private final RowCodec codec;
    private final UnaryOperator<byte[]> rowBytes;
    private Object[] current;

    /** Курсор по ключам строк: значение каждой записи — сама строка. */
    RowCursor(KvIterator iterator, RowCodec codec) {
        this(iterator, codec, UnaryOperator.identity());
    }

    /**
     * @param rowBytes по значению записи — байты строки; для индекса значение — ключ строки,
     *                 и строка читается по нему
     */
    RowCursor(KvIterator iterator, RowCodec codec, UnaryOperator<byte[]> rowBytes) {
        this.iterator = iterator;
        this.codec = codec;
        this.rowBytes = rowBytes;
    }

    public boolean next() {
        if (iterator.next()) {
            current = codec.decodeValue(rowBytes.apply(iterator.value()));
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
