package io.github.roman6666861.swdb.catalog;

import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
import io.github.roman6666861.swdb.storage.KvStore;

/**
 * Данные одной таблицы: вставка, удаление и чтение строк.
 * <p>
 * Пока без транзакций: каждая операция над строкой атомарна сама по себе, а проверка
 * уникальности первичного ключа защищена блокировкой таблицы в пределах процесса.
 */
public final class TableStore {

    private final KvStore store;
    private final TableDef table;
    private final RowCodec codec;
    private final Object lock = new Object();
    private long rowCount = -1; // считается лениво при первом обращении; guarded by lock

    TableStore(KvStore store, TableDef table) {
        this.store = store;
        this.table = table;
        this.codec = new RowCodec(table);
    }

    public TableDef table() {
        return table;
    }

    public void insert(Object[] row) {
        Object[] normalized = codec.normalize(row);
        byte[] key = codec.encodeKey(normalized);
        byte[] value = codec.encodeValue(normalized);
        synchronized (lock) {
            if (store.get(key) != null) {
                throw new SwdbException(SqlStates.UNIQUE_VIOLATION,
                        "duplicate key value violates unique constraint \"" + table.name() + "_pkey\"");
            }
            store.put(key, value);
            if (rowCount >= 0) {
                rowCount++;
            }
        }
    }

    /** Удаляет строку с тем же первичным ключом; {@code false}, если такой строки нет. */
    public boolean delete(Object[] row) {
        byte[] key = codec.encodeKey(codec.normalize(row));
        synchronized (lock) {
            if (store.get(key) == null) {
                return false;
            }
            store.delete(key);
            if (rowCount >= 0) {
                rowCount--;
            }
            return true;
        }
    }

    public RowCursor scan() {
        return new RowCursor(store.scan(Keys.dataPrefix(table.id())), codec);
    }

    public long rowCount() {
        synchronized (lock) {
            if (rowCount < 0) {
                long count = 0;
                try (RowCursor cursor = scan()) {
                    while (cursor.next()) {
                        count++;
                    }
                }
                rowCount = count;
            }
            return rowCount;
        }
    }
}
