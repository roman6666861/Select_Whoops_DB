package io.github.roman6666861.swdb.server.pgwire;

import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PgBinaryTest {

    @Test
    void integersAreBigEndian() {
        assertThat(hex(PgBinary.encode(7, PgBinary.INT4))).isEqualTo("00000007");
        assertThat(hex(PgBinary.encode(-1L, PgBinary.INT8))).isEqualTo("ffffffffffffffff");
        assertThat(hex(PgBinary.encode((short) 258, PgBinary.INT2))).isEqualTo("0102");
        assertThat(PgBinary.decode(bytes("00000007"), PgBinary.INT4)).isEqualTo(7);
        assertThat(PgBinary.decode(bytes("0102"), PgBinary.INT2)).isEqualTo((short) 258);
    }

    @Test
    void datesCountFromYear2000() {
        // Эпоха PostgreSQL — 2000-01-01, а не 1970-01-01.
        assertThat(hex(PgBinary.encode(Date.valueOf("2000-01-01"), PgBinary.DATE))).isEqualTo("00000000");
        assertThat(hex(PgBinary.encode(Date.valueOf("1999-12-31"), PgBinary.DATE))).isEqualTo("ffffffff");
        assertThat(PgBinary.decode(bytes("00000001"), PgBinary.DATE)).isEqualTo(Date.valueOf("2000-01-02"));

        // Микросекунды: 1 секунда = 1 000 000 = 0x0f4240.
        assertThat(hex(PgBinary.encode(Timestamp.valueOf("2000-01-01 00:00:01"), PgBinary.TIMESTAMP)))
                .isEqualTo("00000000000f4240");
    }

    @Test
    void numericIsBase10000Digits() {
        // 12345.678 → ndigits 3, weight 1, sign +, dscale 3, цифры 1 | 2345 | 6780.
        assertThat(hex(PgBinary.encodeNumeric(new BigDecimal("12345.678"))))
                .isEqualTo("0003" + "0001" + "0000" + "0003" + "0001" + "0929" + "1a7c");
        // -0.5 → одна цифра 5000 на позиции 10000^-1.
        assertThat(hex(PgBinary.encodeNumeric(new BigDecimal("-0.5"))))
                .isEqualTo("0001" + "ffff" + "4000" + "0001" + "1388");
        // Ноль: цифр нет, но dscale сохраняется.
        assertThat(hex(PgBinary.encodeNumeric(new BigDecimal("0.00")))).isEqualTo("0000" + "0000" + "0000" + "0002");
    }

    @Test
    void numericRoundTrip() {
        for (String value : new String[]{"0", "1", "-1", "12345.678", "0.0001", "10000", "1.00", "-987654321.123456789",
                "99999999999999999999999.5", "1E+5"}) {
            BigDecimal number = new BigDecimal(value);
            BigDecimal decoded = (BigDecimal) PgBinary.decode(PgBinary.encodeNumeric(number), PgBinary.NUMERIC);
            assertThat(decoded).as(value).isEqualByComparingTo(number);
            assertThat(decoded.scale()).as(value + " scale").isEqualTo(Math.max(number.scale(), 0));
        }
    }

    @Test
    void otherTypesRoundTrip() {
        roundTrip(true, PgBinary.BOOL);
        roundTrip(false, PgBinary.BOOL);
        roundTrip(2.5f, PgBinary.FLOAT4);
        roundTrip(-1.25, PgBinary.FLOAT8);
        roundTrip(Long.MIN_VALUE, PgBinary.INT8);
        roundTrip(Date.valueOf("2026-10-21"), PgBinary.DATE);
        roundTrip(Timestamp.valueOf("2026-10-21 12:30:45.123456"), PgBinary.TIMESTAMP);
        roundTrip(Time.valueOf("12:30:45"), PgBinary.TIME);
        assertThat(PgBinary.decode("ёжик".getBytes(java.nio.charset.StandardCharsets.UTF_8), PgBinary.VARCHAR))
                .isEqualTo("ёжик");
    }

    @Test
    void wrongLengthIsInvalidBinaryRepresentation() {
        for (String data : new String[]{"0001", "0000000100"}) {
            assertThatThrownBy(() -> PgBinary.decode(bytes(data), PgBinary.INT4))
                    .isInstanceOfSatisfying(SwdbException.class,
                            e -> assertThat(e.sqlState()).isEqualTo(SqlStates.INVALID_BINARY_REPRESENTATION));
        }
    }

    private static void roundTrip(Object value, int oid) {
        assertThat(PgBinary.decode(PgBinary.encode(value, oid), oid)).isEqualTo(value);
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static byte[] bytes(String hex) {
        return ByteBuffer.wrap(HexFormat.of().parseHex(hex)).array();
    }
}
