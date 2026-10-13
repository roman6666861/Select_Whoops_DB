package io.github.roman6666861.swdb.catalog;

import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
import io.github.roman6666861.swdb.storage.KeyBuilder;
import io.github.roman6666861.swdb.storage.KvStore;

import java.util.Arrays;
import java.util.Optional;

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

    /**
     * Строка по полному первичному ключу: одно чтение из хранилища, без перебора.
     *
     * @param key значения всех колонок первичного ключа в порядке их объявления в PRIMARY KEY
     */
    public Optional<Object[]> get(Object... key) {
        if (key.length != table.primaryKey().size()) {
            throw new IllegalArgumentException("table " + table.name() + " has " + table.primaryKey().size()
                    + " primary key columns, got " + key.length + " values");
        }
        byte[] value = store.get(codec.encodeKeyPrefix(Arrays.asList(key)));
        return Optional.ofNullable(value).map(codec::decodeValue);
    }

    /** Все строки таблицы в порядке первичного ключа. */
    public RowCursor scan() {
        return scan(null, null);
    }

    /**
     * Строки, первичный ключ которых лежит между {@code lower} и {@code upper}, в порядке ключа.
     * Граница {@code null} означает «без ограничения с этой стороны».
     * <p>
     * Так как ключи в хранилище отсортированы, это один последовательный проход по диапазону
     * {@code [from, to)} без чтения остальных строк. Включительность границ и границы-префиксы
     * сводятся к выбору {@code from}/{@code to} через {@link KeyBuilder#prefixEnd}:
     * <pre>
     *   lower: inclusive → key(lower)            exclusive → prefixEnd(key(lower))
     *   upper: inclusive → prefixEnd(key(upper)) exclusive → key(upper)
     * </pre>
     */
    public RowCursor scan(KeyBound lower, KeyBound upper) {
        byte[] from = lower == null ? Keys.dataPrefix(table.id())
                : lower.inclusive() ? key(lower) : KeyBuilder.prefixEnd(key(lower));
        byte[] to = upper == null ? KeyBuilder.prefixEnd(Keys.dataPrefix(table.id()))
                : upper.inclusive() ? KeyBuilder.prefixEnd(key(upper)) : key(upper);
        return new RowCursor(store.scan(from, to), codec);
    }

    private byte[] key(KeyBound bound) {
        return codec.encodeKeyPrefix(bound.values());
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
