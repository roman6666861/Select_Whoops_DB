package io.github.roman6666861.swdb.storage;

import org.rocksdb.ReadOptions;
import org.rocksdb.RocksIterator;

import java.util.Arrays;

/**
 * Курсор по ключам из диапазона {@code [from, to)} в порядке возрастания. Использование:
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
    private final byte[] from;
    private final byte[] to;
    private final ReadOptions readOptions;
    private boolean started;

    /** {@code to == null} — без верхней границы. */
    KvIterator(RocksIterator iterator, byte[] from, byte[] to) {
        this(iterator, from, to, null);
    }

    /** @param readOptions с какими настройками создан итератор; закрываются вместе с ним */
    KvIterator(RocksIterator iterator, byte[] from, byte[] to, ReadOptions readOptions) {
        this.iterator = iterator;
        this.from = from;
        this.to = to;
        this.readOptions = readOptions;
    }

    /** Переходит к следующей записи; {@code false}, если записей в диапазоне больше нет. */
    public boolean next() {
        if (started) {
            iterator.next();
        } else {
            // seek ставит итератор на первый ключ >= from: дальше идём подряд, без поиска.
            iterator.seek(from);
            started = true;
        }
        return iterator.isValid() && beforeEnd(iterator.key());
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
        if (readOptions != null) {
            readOptions.close();
        }
    }

    /** RocksDB сравнивает ключи как беззнаковые байты, поэтому и мы — через compareUnsigned. */
    private boolean beforeEnd(byte[] key) {
        return to == null || Arrays.compareUnsigned(key, to) < 0;
    }
}
