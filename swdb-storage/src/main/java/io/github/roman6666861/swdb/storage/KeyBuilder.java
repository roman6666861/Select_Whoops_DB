package io.github.roman6666861.swdb.storage;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Собирает ключ из значений так, что побайтовое сравнение ключей совпадает
 * с естественным порядком значений (order-preserving encoding).
 * <p>
 * Благодаря этому RocksDB хранит строки таблицы отсортированными по первичному ключу,
 * а диапазонный запрос по ключу превращается в последовательный скан.
 */
public final class KeyBuilder {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream(32);

    public KeyBuilder appendByte(int value) {
        out.write(value);
        return this;
    }

    public KeyBuilder appendBoolean(boolean value) {
        out.write(value ? 1 : 0);
        return this;
    }

    /** Big-endian с инвертированным знаковым битом: отрицательные числа идут раньше положительных. */
    public KeyBuilder appendInt(int value) {
        int v = value ^ Integer.MIN_VALUE;
        out.write(v >>> 24);
        out.write(v >>> 16);
        out.write(v >>> 8);
        out.write(v);
        return this;
    }

    public KeyBuilder appendLong(long value) {
        long v = value ^ Long.MIN_VALUE;
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) (v >>> shift));
        }
        return this;
    }

    /**
     * IEEE 754: у положительных чисел инвертируем знаковый бит, у отрицательных — все биты.
     * После этого беззнаковое сравнение битов совпадает со сравнением чисел.
     */
    public KeyBuilder appendDouble(double value) {
        long bits = Double.doubleToLongBits(value);
        bits ^= (bits < 0) ? -1L : Long.MIN_VALUE;
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) (bits >>> shift));
        }
        return this;
    }

    /**
     * UTF-8 с экранированием: байт 0x00 записывается как 0x00 0xFF, конец строки — 0x00 0x01.
     * Так строка-префикс («ab») всегда меньше более длинной («abc»), а за строкой можно
     * дописывать следующие компоненты составного ключа.
     */
    public KeyBuilder appendString(String value) {
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            out.write(b);
            if (b == 0) {
                out.write(0xFF);
            }
        }
        out.write(0x00);
        out.write(0x01);
        return this;
    }

    public byte[] build() {
        return out.toByteArray();
    }
}
