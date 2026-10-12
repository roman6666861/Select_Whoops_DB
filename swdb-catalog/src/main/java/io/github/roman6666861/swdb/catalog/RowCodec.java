package io.github.roman6666861.swdb.catalog;

import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
import io.github.roman6666861.swdb.storage.KeyBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Превращает строку таблицы ({@code Object[]} во внутреннем представлении Calcite) в пару
 * ключ-значение для хранилища и обратно.
 * <p>
 * Ключ — первичный ключ в упорядоченном кодировании ({@link KeyBuilder}).
 * Значение — вся строка: байт версии формата, затем по каждой колонке флаг NULL и само значение.
 */
final class RowCodec {

    private static final int FORMAT_VERSION = 1;

    private final TableDef table;

    RowCodec(TableDef table) {
        this.table = table;
    }

    /** Проверяет строку на соответствие схеме и приводит значения к типам колонок. */
    Object[] normalize(Object[] row) {
        List<ColumnDef> columns = table.columns();
        if (row.length != columns.size()) {
            throw new SwdbException(SqlStates.INTERNAL_ERROR,
                    "row has " + row.length + " values, table " + table.name() + " has " + columns.size() + " columns");
        }
        Object[] result = new Object[row.length];
        for (int i = 0; i < row.length; i++) {
            ColumnDef column = columns.get(i);
            if (row[i] == null) {
                if (!column.nullable()) {
                    throw new SwdbException(SqlStates.NOT_NULL_VIOLATION,
                            "null value in column \"" + column.name() + "\" of relation \"" + table.name()
                                    + "\" violates not-null constraint");
                }
                continue;
            }
            result[i] = coerce(column, row[i]);
        }
        return result;
    }

    byte[] encodeKey(Object[] row) {
        KeyBuilder key = Keys.data(table.id());
        for (int index : table.primaryKey()) {
            Object value = row[index];
            switch (table.columns().get(index).type()) {
                case BOOLEAN -> key.appendBoolean((Boolean) value);
                case INTEGER, DATE -> key.appendInt((Integer) value);
                case BIGINT, TIMESTAMP -> key.appendLong((Long) value);
                case DOUBLE -> key.appendDouble((Double) value);
                case VARCHAR -> key.appendString((String) value);
                case DECIMAL -> throw new IllegalStateException("DECIMAL is not allowed in primary key");
            }
        }
        return key.build();
    }

    byte[] encodeValue(Object[] row) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(FORMAT_VERSION);
            for (int i = 0; i < row.length; i++) {
                Object value = row[i];
                out.writeBoolean(value != null);
                if (value != null) {
                    writeValue(out, table.columns().get(i).type(), value);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    Object[] decodeValue(byte[] value) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(value))) {
            int version = in.readUnsignedByte();
            if (version != FORMAT_VERSION) {
                throw new SwdbException(SqlStates.INTERNAL_ERROR, "unknown row format version " + version);
            }
            List<ColumnDef> columns = table.columns();
            Object[] row = new Object[columns.size()];
            for (int i = 0; i < row.length; i++) {
                if (in.readBoolean()) {
                    row[i] = readValue(in, columns.get(i).type());
                }
            }
            return row;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Object coerce(ColumnDef column, Object value) {
        return switch (column.type()) {
            case BOOLEAN -> (Boolean) value;
            case INTEGER, DATE -> ((Number) value).intValue();
            case BIGINT, TIMESTAMP -> ((Number) value).longValue();
            case DOUBLE -> ((Number) value).doubleValue();
            case DECIMAL -> value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
            case VARCHAR -> {
                String s = value.toString();
                if (column.precision() != ColumnDef.UNSPECIFIED && s.length() > column.precision()) {
                    throw new SwdbException(SqlStates.STRING_DATA_RIGHT_TRUNCATION,
                            "value too long for type character varying(" + column.precision() + ")");
                }
                yield s;
            }
        };
    }

    private static void writeValue(DataOutputStream out, ColumnType type, Object value) throws IOException {
        switch (type) {
            case BOOLEAN -> out.writeBoolean((Boolean) value);
            case INTEGER, DATE -> out.writeInt((Integer) value);
            case BIGINT, TIMESTAMP -> out.writeLong((Long) value);
            case DOUBLE -> out.writeDouble((Double) value);
            case DECIMAL -> {
                BigDecimal d = (BigDecimal) value;
                out.writeInt(d.scale());
                writeBytes(out, d.unscaledValue().toByteArray());
            }
            case VARCHAR -> writeBytes(out, ((String) value).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Object readValue(DataInputStream in, ColumnType type) throws IOException {
        return switch (type) {
            case BOOLEAN -> in.readBoolean();
            case INTEGER, DATE -> in.readInt();
            case BIGINT, TIMESTAMP -> in.readLong();
            case DOUBLE -> in.readDouble();
            case DECIMAL -> {
                int scale = in.readInt();
                yield new BigDecimal(new BigInteger(readBytes(in)), scale);
            }
            case VARCHAR -> new String(readBytes(in), StandardCharsets.UTF_8);
        };
    }

    private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        byte[] bytes = new byte[in.readInt()];
        in.readFully(bytes);
        return bytes;
    }
}
