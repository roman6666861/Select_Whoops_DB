package io.github.roman6666861.swdb.server.pgwire;

import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Соответствие JDBC-типов типам PostgreSQL (OID из pg_type) и их текстовое представление.
 *
 * @param oid  OID типа
 * @param size размер в байтах, -1 — переменной длины
 */
record PgType(int oid, short size) {

    private static final PgType BOOL = new PgType(16, (short) 1);
    private static final PgType BYTEA = new PgType(17, (short) -1);
    private static final PgType INT8 = new PgType(20, (short) 8);
    private static final PgType INT2 = new PgType(21, (short) 2);
    private static final PgType INT4 = new PgType(23, (short) 4);
    private static final PgType TEXT = new PgType(25, (short) -1);
    private static final PgType FLOAT4 = new PgType(700, (short) 4);
    private static final PgType FLOAT8 = new PgType(701, (short) 8);
    private static final PgType DATE = new PgType(1082, (short) 4);
    private static final PgType TIME = new PgType(1083, (short) 8);
    private static final PgType TIMESTAMP = new PgType(1114, (short) 8);
    private static final PgType NUMERIC = new PgType(1700, (short) -1);

    /** {@code +03}, {@code -05:30}, {@code +0530} или {@code Z} сразу после секунд: {@code 12:30:45.123+03}. */
    private static final Pattern TIME_ZONE_SUFFIX =
            Pattern.compile("(?<=:\\d{2}(?:\\.\\d{1,9})?)\\s*(?:[+-]\\d{2}(?::?\\d{2})?|Z)$");

    static PgType of(int jdbcType) {
        return switch (jdbcType) {
            case Types.BOOLEAN, Types.BIT -> BOOL;
            case Types.TINYINT, Types.SMALLINT -> INT2;
            case Types.INTEGER -> INT4;
            case Types.BIGINT -> INT8;
            case Types.REAL -> FLOAT4;
            case Types.FLOAT, Types.DOUBLE -> FLOAT8;
            case Types.DECIMAL, Types.NUMERIC -> NUMERIC;
            case Types.DATE -> DATE;
            case Types.TIME -> TIME;
            case Types.TIMESTAMP -> TIMESTAMP;
            case Types.BINARY, Types.VARBINARY -> BYTEA;
            default -> TEXT;
        };
    }

    /**
     * Значение параметра из текстового формата протокола в Java-тип, который ждёт Calcite
     * для параметра этого типа ({@link java.sql.Types}).
     */
    static Object fromText(String text, int jdbcType) {
        try {
            return switch (jdbcType) {
                case Types.BOOLEAN, Types.BIT -> parseBoolean(text);
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> Integer.parseInt(text.strip());
                case Types.BIGINT -> Long.parseLong(text.strip());
                case Types.REAL, Types.FLOAT, Types.DOUBLE -> Double.parseDouble(text.strip());
                case Types.DECIMAL, Types.NUMERIC -> new BigDecimal(text.strip());
                case Types.DATE -> java.sql.Date.valueOf(text.strip().split("\\s+")[0]); // "2026-01-02 +03"
                case Types.TIMESTAMP -> java.sql.Timestamp.valueOf(withoutTimeZone(text));
                default -> text;
            };
        } catch (IllegalArgumentException e) { // и NumberFormatException
            throw new SwdbException(SqlStates.INVALID_TEXT_REPRESENTATION,
                    "invalid input syntax for type " + name(jdbcType) + ": \"" + text + "\"", e);
        }
    }

    /**
     * Приводит значение, раскодированное по типу клиента, к Java-типу параметра Calcite.
     * Клиент может прислать {@code int4}, а Calcite ждать BIGINT, или {@code float8} для DECIMAL.
     */
    static Object toParameter(Object value, int jdbcType) {
        try {
            return switch (jdbcType) {
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> value instanceof Number n
                        ? Integer.valueOf(Math.toIntExact(exactLong(n))) : fromText(value.toString(), jdbcType);
                case Types.BIGINT -> value instanceof Number n ? Long.valueOf(exactLong(n)) : fromText(value.toString(), jdbcType);
                case Types.REAL, Types.FLOAT, Types.DOUBLE -> value instanceof Number n
                        ? Double.valueOf(n.doubleValue()) : fromText(value.toString(), jdbcType);
                case Types.DECIMAL, Types.NUMERIC -> value instanceof Number n
                        ? new BigDecimal(n.toString()) : fromText(value.toString(), jdbcType);
                case Types.BOOLEAN, Types.BIT -> value instanceof Boolean ? value : fromText(value.toString(), jdbcType);
                case Types.DATE -> value instanceof java.sql.Timestamp ts ? java.sql.Date.valueOf(ts.toLocalDateTime().toLocalDate())
                        : value instanceof java.sql.Date ? value : fromText(value.toString(), jdbcType);
                case Types.TIMESTAMP -> value instanceof java.sql.Date d ? java.sql.Timestamp.valueOf(d.toLocalDate().atStartOfDay())
                        : value instanceof java.sql.Timestamp ? value : fromText(value.toString(), jdbcType);
                case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR -> value instanceof byte[] ? value : value.toString();
                default -> value;
            };
        } catch (ArithmeticException e) {
            throw new SwdbException(SqlStates.NUMERIC_VALUE_OUT_OF_RANGE, "value " + value + " is out of range for type "
                    + name(jdbcType), e);
        }
    }

    /** Целое значение числа; 2.5 в целочисленный параметр не превращается молча в 2. */
    private static long exactLong(Number n) {
        return switch (n) {
            case Byte _, Short _, Integer _, Long _ -> n.longValue();
            default -> new BigDecimal(n.toString()).longValueExact();
        };
    }

    /**
     * JDBC-драйвер передаёт дату и время с часовым поясом клиента: {@code 2026-01-02 03:04:05+03}.
     * Наш TIMESTAMP — без часового пояса, и PostgreSQL для такого типа указание пояса просто
     * отбрасывает; так же поступаем мы.
     */
    private static String withoutTimeZone(String text) {
        return TIME_ZONE_SUFFIX.matcher(text.strip()).replaceFirst("");
    }

    /** Как PostgreSQL понимает логическое значение в тексте: t/f, true/false, yes/no, on/off, 1/0. */
    private static boolean parseBoolean(String text) {
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "t", "true", "y", "yes", "on", "1" -> true;
            case "f", "false", "n", "no", "off", "0" -> false;
            default -> throw new IllegalArgumentException(text);
        };
    }

    private static String name(int jdbcType) {
        return switch (jdbcType) {
            case Types.BOOLEAN, Types.BIT -> "boolean";
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> "integer";
            case Types.BIGINT -> "bigint";
            case Types.REAL, Types.FLOAT, Types.DOUBLE -> "double precision";
            case Types.DECIMAL, Types.NUMERIC -> "numeric";
            case Types.DATE -> "date";
            case Types.TIMESTAMP -> "timestamp";
            default -> "text";
        };
    }

    /** Текстовый формат значения в протоколе PostgreSQL; {@code null} — SQL NULL. */
    static String toText(Object value) {
        return switch (value) {
            case null -> null;
            case Boolean b -> b ? "t" : "f";
            case BigDecimal d -> d.toPlainString();
            case byte[] bytes -> "\\x" + HexFormat.of().formatHex(bytes);
            default -> value.toString();
        };
    }
}
