package io.github.roman6666861.swdb.storage;

import java.util.Arrays;
import java.util.Objects;

/**
 * Неизменяемый диапазон закодированных ключей для будущих блокировок диапазонов.
 * Нижняя граница включена, верхняя исключена: {@code [from, to)}.
 * Ключи сравниваются побайтово без знака, как в RocksDB.
 * <p>
 * Пустой массив в {@code from} — начало пространства ключей;
 * {@code to == null} — отсутствие верхней границы.
 * Равные границы задают пустой диапазон, обратный порядок запрещён.
 * Сам по себе этот объект блокировок не захватывает.
 */
public final class LockRange {

    private final byte[] from;
    private final byte[] to;

    public LockRange(byte[] from, byte[] to) {
        this.from = Objects.requireNonNull(from, "from").clone();
        this.to = to == null ? null : to.clone();
        if (this.to != null && Arrays.compareUnsigned(this.from, this.to) > 0) {
            throw new IllegalArgumentException("from must not be greater than to");
        }
    }

    /** Ровно один полный ключ, без его продолжений в составных ключах. */
    public static LockRange point(byte[] key) {
        Objects.requireNonNull(key, "key");
        // В лексикографическом порядке key + 00 — ближайший ключ после key.
        return new LockRange(key, Arrays.copyOf(key, key.length + 1));
    }

    /** Все ключи, начинающиеся с prefix, включая сам prefix. */
    public static LockRange prefix(byte[] prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return new LockRange(prefix, KeyBuilder.prefixEnd(prefix));
    }

    public byte[] from() {
        return from.clone();
    }

    public byte[] to() {
        return to == null ? null : to.clone();
    }

    public boolean isEmpty() {
        return to != null && Arrays.equals(from, to);
    }

    /** Есть ли хотя бы один общий ключ; соприкасающиеся границы не пересекаются. */
    public boolean overlaps(LockRange other) {
        Objects.requireNonNull(other, "other");
        return !isEmpty() && !other.isEmpty()
                && (to == null || Arrays.compareUnsigned(other.from, to) < 0)
                && (other.to == null || Arrays.compareUnsigned(from, other.to) < 0);
    }
}
