package io.github.roman6666861.swdb.storage;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

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

    /** Наибольшая точность DECIMAL, которую умеет {@link #appendDecimal}: 10^38 − 1 < 2^127. */
    public static final int MAX_DECIMAL_PRECISION = 38;

    /**
     * DECIMAL(precision, scale). Значение приводится к масштабу колонки, и записывается целое
     * число {@code value · 10^scale}: так {@code 1.5} и {@code 1.50} дают одинаковые байты,
     * а порядок целых совпадает с порядком десятичных при одном масштабе.
     * <p>
     * При {@code precision <= 18} целое помещается в long (10^18 − 1 < 2^63) и пишется как
     * {@link #appendLong}; иначе — 16 байтами дополнительного кода с инвертированным знаковым битом,
     * по тому же принципу. Длина зависит только от точности колонки, поэтому за значением
     * можно дописывать следующие компоненты ключа.
     *
     * @throws ArithmeticException если у значения больше знаков после запятой, чем {@code scale},
     *                             или оно не помещается в {@code precision} цифр
     */
    public KeyBuilder appendDecimal(BigDecimal value, int precision, int scale) {
        if (precision > MAX_DECIMAL_PRECISION) {
            throw new IllegalArgumentException("DECIMAL precision " + precision + " is above " + MAX_DECIMAL_PRECISION);
        }
        BigInteger unscaled = value.setScale(scale, RoundingMode.UNNECESSARY).unscaledValue();
        if (unscaled.abs().compareTo(BigInteger.TEN.pow(precision)) >= 0) {
            throw new ArithmeticException(value + " does not fit DECIMAL(" + precision + ", " + scale + ")");
        }
        if (precision <= 18) {
            return appendLong(unscaled.longValue());
        }
        // toByteArray — минимальный дополнительный код; дополняем слева знаком до 16 байт.
        byte[] minimal = unscaled.toByteArray();
        byte[] bytes = new byte[16];
        Arrays.fill(bytes, 0, 16 - minimal.length, unscaled.signum() < 0 ? (byte) 0xFF : 0);
        System.arraycopy(minimal, 0, bytes, 16 - minimal.length, minimal.length);
        bytes[0] ^= (byte) 0x80;
        out.writeBytes(bytes);
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

    /**
     * Наименьший ключ, который больше всех ключей, начинающихся с {@code prefix}: последний байт,
     * не равный 0xFF, увеличивается на 1, а всё после него отбрасывается ({@code 61 FF} → {@code 62}).
     * Тогда «все ключи с префиксом» — это диапазон {@code [prefix, prefixEnd(prefix))}.
     *
     * @return {@code null}, если префикс состоит только из 0xFF и верхней границы нет
     */
    public static byte[] prefixEnd(byte[] prefix) {
        for (int i = prefix.length - 1; i >= 0; i--) {
            if (prefix[i] != (byte) 0xFF) {
                byte[] end = Arrays.copyOf(prefix, i + 1);
                end[i]++;
                return end;
            }
        }
        return null;
    }
}
