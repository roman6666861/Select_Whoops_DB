package io.github.roman6666861.swdb.sql;

/**
 * Уровни изоляции транзакции, как в PostgreSQL. {@code READ UNCOMMITTED} в PostgreSQL ведёт себя
 * как {@code READ COMMITTED} («грязного чтения» там нет), у нас так же.
 */
enum IsolationLevel {

    /** Свой снимок у каждого оператора: оператор видит всё, что зафиксировано до его начала. */
    READ_COMMITTED,
    /** Один снимок на всю транзакцию, взятый при BEGIN. */
    REPEATABLE_READ,
    /**
     * Как REPEATABLE READ, плюс двухфазные блокировки таблиц между SERIALIZABLE-транзакциями
     * ({@code Catalog.lockSerializable}): результат такой, будто они выполнялись по очереди.
     */
    SERIALIZABLE;

    /** Уровень по умолчанию для BEGIN, как в PostgreSQL. */
    static final IsolationLevel DEFAULT = READ_COMMITTED;
}
