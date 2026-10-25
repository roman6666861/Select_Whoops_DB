package io.github.roman6666861.swdb.server.pgwire;

import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Двоичный формат значений протокола PostgreSQL (формат 1 в Bind и RowDescription).
 * <p>
 * Числа — big-endian, как в сети. Даты и время считаются от эпохи PostgreSQL —
 * 2000-01-01, а не от 1970-01-01, как в Java.
 */
final class PgBinary {

    static final int BOOL = 16;
    static final int BYTEA = 17;
    static final int INT8 = 20;
    static final int INT2 = 21;
    static final int INT4 = 23;
    static final int TEXT = 25;
    static final int FLOAT4 = 700;
    static final int FLOAT8 = 701;
    static final int BPCHAR = 1042;
    static final int VARCHAR = 1043;
    static final int DATE = 1082;
    static final int TIME = 1083;
    static final int TIMESTAMP = 1114;
    static final int NUMERIC = 1700;

    private static final LocalDate POSTGRES_EPOCH = LocalDate.of(2000, 1, 1);
    private static final LocalDateTime POSTGRES_EPOCH_TIME = POSTGRES_EPOCH.atStartOfDay();

    /** numeric хранится «цифрами» по основанию 10000: по 4 десятичных знака в каждой. */
    private static final BigInteger NBASE = BigInteger.valueOf(10_000);
    private static final short NUMERIC_POSITIVE = 0x0000;
    private static final short NUMERIC_NEGATIVE = 0x4000;

    private PgBinary() {
    }

    /** Значение параметра в двоичном формате типа {@code oid} — в естественный Java-тип. */
    static Object decode(byte[] bytes, int oid) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        try {
            Object value = switch (oid) {
                case BOOL -> buf.get() != 0;
                case INT2 -> buf.getShort();
                case INT4 -> buf.getInt();
                case INT8 -> buf.getLong();
                case FLOAT4 -> buf.getFloat();
                case FLOAT8 -> buf.getDouble();
                case TEXT, VARCHAR, BPCHAR -> new String(rest(buf), StandardCharsets.UTF_8);
                case BYTEA -> rest(buf);
                case DATE -> Date.valueOf(POSTGRES_EPOCH.plusDays(buf.getInt()));
                case TIME -> Time.valueOf(LocalTime.ofNanoOfDay(buf.getLong() * 1000));
                case TIMESTAMP -> Timestamp.valueOf(POSTGRES_EPOCH_TIME.plus(buf.getLong(), ChronoUnit.MICROS));
                case NUMERIC -> decodeNumeric(buf);
                default -> throw new SwdbException(SqlStates.FEATURE_NOT_SUPPORTED,
                        "binary format of type with OID " + oid + " is not supported yet");
            };
            if (buf.hasRemaining()) {
                throw invalid(oid);
            }
            return value;
        } catch (java.nio.BufferUnderflowException e) {
            throw invalid(oid);
        }
    }

    /** Значение колонки в двоичном формате типа {@code oid}. */
    static byte[] encode(Object value, int oid) {
        return switch (oid) {
            case BOOL -> new byte[]{(byte) (((Boolean) value) ? 1 : 0)};
            case INT2 -> ByteBuffer.allocate(2).putShort(((Number) value).shortValue()).array();
            case INT4 -> ByteBuffer.allocate(4).putInt(((Number) value).intValue()).array();
            case INT8 -> ByteBuffer.allocate(8).putLong(((Number) value).longValue()).array();
            case FLOAT4 -> ByteBuffer.allocate(4).putFloat(((Number) value).floatValue()).array();
            case FLOAT8 -> ByteBuffer.allocate(8).putDouble(((Number) value).doubleValue()).array();
            case BYTEA -> (byte[]) value;
            case DATE -> ByteBuffer.allocate(4)
                    .putInt(Math.toIntExact(ChronoUnit.DAYS.between(POSTGRES_EPOCH, ((Date) value).toLocalDate())))
                    .array();
            case TIME -> ByteBuffer.allocate(8).putLong(((Time) value).toLocalTime().toNanoOfDay() / 1000).array();
            case TIMESTAMP -> ByteBuffer.allocate(8)
                    .putLong(ChronoUnit.MICROS.between(POSTGRES_EPOCH_TIME, ((Timestamp) value).toLocalDateTime()))
                    .array();
            case NUMERIC -> encodeNumeric((BigDecimal) value);
            default -> PgType.toText(value).getBytes(StandardCharsets.UTF_8); // text, varchar
        };
    }

    /**
     * {@code Int16 ndigits, Int16 weight, Int16 sign, Int16 dscale, Int16[ndigits] digits}.
     * Число = Σ digits[i] × 10000^(weight − i); dscale — сколько знаков после запятой показывать.
     * Например, 12345.678 = [1, 2345, 6780], weight 1, dscale 3.
     */
    static byte[] encodeNumeric(BigDecimal value) {
        int dscale = Math.max(value.scale(), 0);
        BigDecimal abs = value.abs().setScale(dscale, RoundingMode.UNNECESSARY);
        // Дробную часть дополняем нулями до кратного 4 числа знаков: так она делится на «цифры» 10000.
        int fractionGroups = (dscale + 3) / 4;
        BigInteger scaled = abs.movePointRight(fractionGroups * 4).toBigIntegerExact();
        List<Short> digits = new ArrayList<>();
        while (scaled.signum() > 0) {
            BigInteger[] qr = scaled.divideAndRemainder(NBASE);
            digits.addFirst(qr[1].shortValue());
            scaled = qr[0];
        }
        int weight = digits.size() - fractionGroups - 1;
        // Нули в конце не хранятся: их восстанавливает dscale.
        while (!digits.isEmpty() && digits.getLast() == 0) {
            digits.removeLast();
        }
        if (digits.isEmpty()) {
            weight = 0;
        }
        ByteBuffer buf = ByteBuffer.allocate(8 + 2 * digits.size());
        buf.putShort((short) digits.size());
        buf.putShort((short) weight);
        buf.putShort(value.signum() < 0 ? NUMERIC_NEGATIVE : NUMERIC_POSITIVE);
        buf.putShort((short) dscale);
        digits.forEach(buf::putShort);
        return buf.array();
    }

    private static BigDecimal decodeNumeric(ByteBuffer buf) {
        int ndigits = buf.getShort();
        int weight = buf.getShort();
        int sign = buf.getShort() & 0xFFFF;
        int dscale = buf.getShort();
        if (sign != NUMERIC_POSITIVE && sign != NUMERIC_NEGATIVE) {
            throw new SwdbException(SqlStates.FEATURE_NOT_SUPPORTED, "numeric NaN and infinity are not supported");
        }
        BigInteger unscaled = BigInteger.ZERO;
        for (int i = 0; i < ndigits; i++) {
            unscaled = unscaled.multiply(NBASE).add(BigInteger.valueOf(buf.getShort()));
        }
        // Последняя цифра стоит на позиции weight − (ndigits − 1), то есть умножена на 10000^это.
        BigDecimal value = new BigDecimal(unscaled).movePointRight(4 * (weight - ndigits + 1));
        value = value.setScale(dscale, RoundingMode.UNNECESSARY);
        return sign == NUMERIC_NEGATIVE ? value.negate() : value;
    }

    /** Значения переменной длины занимают всё, что осталось. */
    private static byte[] rest(ByteBuffer buf) {
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        return bytes;
    }

    private static SwdbException invalid(int oid) {
        return new SwdbException(SqlStates.INVALID_BINARY_REPRESENTATION,
                "incorrect binary data format for type with OID " + oid);
    }
}
