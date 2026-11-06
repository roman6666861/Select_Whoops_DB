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
    private boolean serializable;

    public void set(KvTransaction transaction) {
        set(transaction, false);
    }

    /** @param serializable транзакция SERIALIZABLE: таблицы блокируются {@code SerializableLocks.lockTable} */
    public void set(KvTransaction transaction, boolean serializable) {
        if (current != null) {
            throw new IllegalStateException("transaction is already set");
        }
        current = transaction;
        this.serializable = serializable;
    }

    public void clear() {
        current = null;
        serializable = false;
    }

    boolean serializable() {
        return serializable;
    }

    public KvTransaction current() {
        if (current == null) {
            throw new IllegalStateException("no active transaction");
        }
        return current;
    }

    /** Текущая транзакция или {@code null}: оператор готовится заранее ({@code prepare}) вне BEGIN. */
    KvTransaction currentOrNull() {
        return current;
    }
}
