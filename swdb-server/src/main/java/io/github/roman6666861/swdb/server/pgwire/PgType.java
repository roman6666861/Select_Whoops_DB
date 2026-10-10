package io.github.roman6666861.swdb.server.pgwire;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.HexFormat;

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
