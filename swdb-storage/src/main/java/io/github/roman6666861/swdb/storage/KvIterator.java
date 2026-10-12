package io.github.roman6666861.swdb.storage;

import org.rocksdb.RocksIterator;

import java.util.Arrays;

/**
 * Курсор по ключам с общим префиксом. Использование:
 * <pre>{@code
 * try (KvIterator it = store.scan(prefix)) {
 *     while (it.next()) {
 *         use(it.key(), it.value());
 *     }
 * }
 * }</pre>
 */
public final class KvIterator implements AutoCloseable {

    private final RocksIterator iterator;
    private final byte[] prefix;
    private boolean started;

    KvIterator(RocksIterator iterator, byte[] prefix) {
        this.iterator = iterator;
        this.prefix = prefix;
    }

    /** Переходит к следующей записи; {@code false}, если записей с префиксом больше нет. */
    public boolean next() {
        if (started) {
            iterator.next();
        } else {
            iterator.seek(prefix);
            started = true;
        }
        return iterator.isValid() && hasPrefix(iterator.key());
    }

    public byte[] key() {
        return iterator.key();
    }

    public byte[] value() {
        return iterator.value();
    }

    @Override
    public void close() {
        iterator.close();
    }

    private boolean hasPrefix(byte[] key) {
        return key.length >= prefix.length
                && Arrays.equals(key, 0, prefix.length, prefix, 0, prefix.length);
    }
}
