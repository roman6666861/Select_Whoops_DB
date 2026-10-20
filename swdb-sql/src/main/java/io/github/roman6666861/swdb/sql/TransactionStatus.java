package io.github.roman6666861.swdb.sql;

/**
 * Состояние транзакции сессии; сервер сообщает его клиенту после каждой команды.
 */
public enum TransactionStatus {

    /** Вне транзакции: каждый оператор выполняется и фиксируется сам по себе. */
    IDLE,

    /** Внутри {@code BEGIN ... COMMIT}. */
    IN_TRANSACTION,

    /** Внутри транзакции, где случилась ошибка: принимаются только COMMIT и ROLLBACK. */
    FAILED
}
