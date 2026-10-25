package io.github.roman6666861.swdb.server.pgwire;

import io.github.roman6666861.swdb.sql.Column;
import io.netty.buffer.ByteBuf;

import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Пишет сообщения сервера (backend) протокола PostgreSQL v3 в буфер.
 */
final class PgMessageWriter {

    /** Статус транзакции в ReadyForQuery: вне транзакции. */
    static final char IDLE = 'I';
    /** Внутри BEGIN ... COMMIT. */
    static final char IN_TRANSACTION = 'T';
    /** В сломанной транзакции: сервер ждёт ROLLBACK. */
    static final char FAILED_TRANSACTION = 'E';

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

    /** Все колонки в текстовом формате. */
    PgMessageWriter rowDescription(List<Column> columns) {
        return rowDescription(columns, List.of());
    }

    /** @param binary для каждой колонки — в двоичном ли она формате; пустой список — все текстовые */
    PgMessageWriter rowDescription(List<Column> columns, List<Boolean> binary) {
        begin('T');
        buf.writeShort(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            PgType type = PgType.of(columns.get(i).jdbcType());
            cstring(columns.get(i).name());
            buf.writeInt(0);             // OID таблицы
            buf.writeShort(0);           // номер колонки в таблице
            buf.writeInt(type.oid());
            buf.writeShort(type.size());
            buf.writeInt(-1);            // type modifier
            buf.writeShort(isBinary(binary, i) ? 1 : 0);
        }
        return end();
    }

    /** Все значения в текстовом формате. */
    PgMessageWriter dataRow(List<Object> values) {
        return dataRow(values, List.of(), List.of());
    }

    /**
     * @param columns колонки — по их типу выбирается двоичное представление
     * @param binary  для каждой колонки — в двоичном ли она формате; пустой список — все текстовые
     */
    PgMessageWriter dataRow(List<Object> values, List<Column> columns, List<Boolean> binary) {
        begin('D');
        buf.writeShort(values.size());
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (value == null) {
                buf.writeInt(-1);
                continue;
            }
            byte[] bytes = isBinary(binary, i)
                    ? PgBinary.encode(value, PgType.of(columns.get(i).jdbcType()).oid())
                    : PgType.toText(value).getBytes(UTF_8);
            buf.writeInt(bytes.length);
            buf.writeBytes(bytes);
        }
        return end();
    }

    private static boolean isBinary(List<Boolean> binary, int column) {
        return !binary.isEmpty() && binary.get(column);
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

    // ---- extended query protocol ----

    PgMessageWriter parseComplete() {
        begin('1');
        return end();
    }

    PgMessageWriter bindComplete() {
        begin('2');
        return end();
    }

    PgMessageWriter closeComplete() {
        begin('3');
        return end();
    }

    /** Ответ на Describe оператора: OID типа каждого параметра. */
    PgMessageWriter parameterDescription(List<Integer> typeOids) {
        begin('t');
        buf.writeShort(typeOids.size());
        typeOids.forEach(buf::writeInt);
        return end();
    }

    /** Ответ на Describe оператора или портала, который не возвращает строк. */
    PgMessageWriter noData() {
        begin('n');
        return end();
    }

    /** Execute выдал столько строк, сколько просил клиент, но строки ещё остались. */
    PgMessageWriter portalSuspended() {
        begin('s');
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
