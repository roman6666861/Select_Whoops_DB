package io.github.roman6666861.swdb.server.pgwire;

import io.github.roman6666861.swdb.sql.Column;
import io.netty.buffer.ByteBuf;

import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Пишет сообщения сервера (backend) протокола PostgreSQL v3 в буфер.
 */
final class PgMessageWriter {

    static final char IDLE = 'I';

    private final ByteBuf buf;
    private int lengthIndex;

    PgMessageWriter(ByteBuf buf) {
        this.buf = buf;
    }

    PgMessageWriter authenticationOk() {
        begin('R');
        buf.writeInt(0);
        return end();
    }

    PgMessageWriter parameterStatus(String name, String value) {
        begin('S');
        cstring(name);
        cstring(value);
        return end();
    }

    PgMessageWriter backendKeyData(int processId, int secretKey) {
        begin('K');
        buf.writeInt(processId);
        buf.writeInt(secretKey);
        return end();
    }

    PgMessageWriter readyForQuery(char transactionStatus) {
        begin('Z');
        buf.writeByte(transactionStatus);
        return end();
    }

    PgMessageWriter rowDescription(List<Column> columns) {
        begin('T');
        buf.writeShort(columns.size());
        for (Column column : columns) {
            PgType type = PgType.of(column.jdbcType());
            cstring(column.name());
            buf.writeInt(0);             // OID таблицы
            buf.writeShort(0);           // номер колонки в таблице
            buf.writeInt(type.oid());
            buf.writeShort(type.size());
            buf.writeInt(-1);            // type modifier
            buf.writeShort(0);           // формат: текст
        }
        return end();
    }

    PgMessageWriter dataRow(List<Object> values) {
        begin('D');
        buf.writeShort(values.size());
        for (Object value : values) {
            String text = PgType.toText(value);
            if (text == null) {
                buf.writeInt(-1);
            } else {
                byte[] bytes = text.getBytes(UTF_8);
                buf.writeInt(bytes.length);
                buf.writeBytes(bytes);
            }
        }
        return end();
    }

    PgMessageWriter commandComplete(String tag) {
        begin('C');
        cstring(tag);
        return end();
    }

    PgMessageWriter emptyQueryResponse() {
        begin('I');
        return end();
    }

    PgMessageWriter errorResponse(String sqlState, String message) {
        begin('E');
        buf.writeByte('S');
        cstring("ERROR");
        buf.writeByte('V');
        cstring("ERROR");
        buf.writeByte('C');
        cstring(sqlState);
        buf.writeByte('M');
        cstring(message == null ? "unknown error" : message);
        buf.writeByte(0);
        return end();
    }

    private void begin(char type) {
        buf.writeByte(type);
        lengthIndex = buf.writerIndex();
        buf.writeInt(0);
    }

    private PgMessageWriter end() {
        buf.setInt(lengthIndex, buf.writerIndex() - lengthIndex);
        return this;
    }

    private void cstring(String value) {
        buf.writeCharSequence(value, UTF_8);
        buf.writeByte(0);
    }
}
