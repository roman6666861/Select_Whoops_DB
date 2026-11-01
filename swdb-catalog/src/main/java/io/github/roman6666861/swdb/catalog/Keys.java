package io.github.roman6666861.swdb.catalog;

import io.github.roman6666861.swdb.storage.KeyBuilder;

/**
 * Раскладка ключей в хранилище:
 * <pre>
 *   'M' 'T' [tableId]                                    → описание таблицы (JSON)
 *   'M' 'N' [имя]                                        → 'T' или 'I' (таблица или индекс), [tableId]
 *   'D' [tableId] [pk...]                                → строка таблицы
 *   'I' [tableId] [indexId] [значения колонок...] [pk...] → ключ строки 'D' [tableId] [pk...]
 * </pre>
 * {@code tableId} и {@code indexId} — 4 байта big-endian, поэтому данные одной таблицы и записи
 * одного индекса лежат непрерывными диапазонами.
 * <p>
 * В записи индекса первичный ключ дописан в конец ключа, чтобы строки с одинаковым значением
 * колонки не затирали друг друга, а в значении лежит готовый ключ строки — по нему строка
 * читается одним {@code get}, без разбора ключа индекса.
 * <p>
 * Ключ имени {@code 'M' 'N'} — общий для таблиц и индексов, как пространство имён «relations»
 * в PostgreSQL. По нему таблица находится по имени, а две транзакции, создающие одно имя,
 * упираются в блокировку одного ключа.
 */
final class Keys {

    private static final int META = 'M';
    private static final int META_TABLE = 'T';
    private static final int META_NAME = 'N';
    private static final int DATA = 'D';
    private static final int INDEX = 'I';

    private Keys() {
    }

    static byte[] tableMetaPrefix() {
        return new KeyBuilder().appendByte(META).appendByte(META_TABLE).build();
    }

    static byte[] tableMeta(int tableId) {
        return new KeyBuilder().appendByte(META).appendByte(META_TABLE).appendInt(tableId).build();
    }

    static byte[] relationName(String name) {
        return new KeyBuilder().appendByte(META).appendByte(META_NAME).appendString(name).build();
    }

    static KeyBuilder data(int tableId) {
        return new KeyBuilder().appendByte(DATA).appendInt(tableId);
    }

    static byte[] dataPrefix(int tableId) {
        return data(tableId).build();
    }

    static KeyBuilder index(int tableId, int indexId) {
        return new KeyBuilder().appendByte(INDEX).appendInt(tableId).appendInt(indexId);
    }

    static byte[] indexPrefix(int tableId, int indexId) {
        return index(tableId, indexId).build();
    }
}
