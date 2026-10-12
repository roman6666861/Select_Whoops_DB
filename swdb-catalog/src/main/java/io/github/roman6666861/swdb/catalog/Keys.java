package io.github.roman6666861.swdb.catalog;

import io.github.roman6666861.swdb.storage.KeyBuilder;

/**
 * Раскладка ключей в хранилище:
 * <pre>
 *   'M' 'T' [tableId]          → описание таблицы (JSON)
 *   'D' [tableId] [pk...]      → строка таблицы
 * </pre>
 * {@code tableId} — 4 байта big-endian, поэтому данные одной таблицы лежат непрерывным диапазоном
 * {@code [dataPrefix(id), dataPrefix(id + 1))}.
 */
final class Keys {

    private static final int META = 'M';
    private static final int META_TABLE = 'T';
    private static final int DATA = 'D';

    private Keys() {
    }

    static byte[] tableMetaPrefix() {
        return new KeyBuilder().appendByte(META).appendByte(META_TABLE).build();
    }

    static byte[] tableMeta(int tableId) {
        return new KeyBuilder().appendByte(META).appendByte(META_TABLE).appendInt(tableId).build();
    }

    static KeyBuilder data(int tableId) {
        return new KeyBuilder().appendByte(DATA).appendInt(tableId);
    }

    static byte[] dataPrefix(int tableId) {
        return data(tableId).build();
    }
}
