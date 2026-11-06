package io.github.roman6666861.swdb.catalog;

import io.github.roman6666861.swdb.storage.KvTransaction;

/**
 * Двухфазные блокировки таблиц между SERIALIZABLE-транзакциями.
 * Чтение берёт разделяемую блокировку, запись — исключительную.
 * Блокировки принадлежат переданной транзакции RocksDB и держатся до её завершения;
 * отдельного состояния или ручного освобождения здесь нет.
 * <p>
 * Писатель также записывает ключ блокировки: если он зафиксировался после снимка
 * другой транзакции, её последующий захват блокировки обнаружит конфликт снимка
 * и вернёт {@code 40001}. Одного ожидания блокировки для этого недостаточно.
 * Два читателя, одновременно переходящих к записи, могут образовать взаимную
 * блокировку; её обнаруживает RocksDB, а SQL-слой переводит ошибку в {@code 40001}.
 * <p>
 * Транзакции других уровней этот механизм не используют: гарантия действует
 * между SERIALIZABLE-транзакциями. DDL отдельно защищает описание таблицы
 * через {@link Catalog#openTable} и исключительную блокировку метаданных.
 */
public final class SerializableLocks {

    private SerializableLocks() {
    }

    public static void lockTable(KvTransaction tx, int tableId, boolean write) {
        byte[] key = Keys.serializableLock(tableId);
        if (write) {
            tx.getForUpdate(key);
            tx.put(key, new byte[0]);
        } else {
            tx.getForShare(key);
        }
    }
}
