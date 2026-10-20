package io.github.roman6666861.swdb.sql.schema;

import io.github.roman6666861.swdb.storage.KvTransaction;

/**
 * Текущая транзакция сессии. Таблицы Calcite создаются в каждой сессии свои и берут транзакцию
 * отсюда: так все чтения и изменения одного оператора идут через одну транзакцию.
 * <p>
 * Транзакцию начинает и завершает {@code SqlSession}; таблицы только пользуются ею.
 * Не потокобезопасен, как и сессия.
 */
public final class SessionTransaction {

    private KvTransaction current;

    public void set(KvTransaction transaction) {
        if (current != null) {
            throw new IllegalStateException("transaction is already set");
        }
        current = transaction;
    }

    public void clear() {
        current = null;
    }

    KvTransaction current() {
        if (current == null) {
            throw new IllegalStateException("no active transaction");
        }
        return current;
    }
}
