package io.github.roman6666861.swdb.sql;

import io.github.roman6666861.swdb.catalog.Catalog;
import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
import io.github.roman6666861.swdb.sql.schema.SessionTransaction;
import io.github.roman6666861.swdb.storage.KvTransaction;

import java.util.function.Supplier;

/**
 * Транзакции сессии так, как их видит клиент PostgreSQL.
 * <ul>
 *   <li>Без {@code BEGIN} каждый оператор выполняется в своей транзакции и сразу фиксируется.</li>
 *   <li>После {@code BEGIN} одна транзакция живёт между операторами до {@code COMMIT} или
 *       {@code ROLLBACK}; её изменения до COMMIT не видны другим сессиям.</li>
 *   <li>Ошибка внутри транзакции её «ломает»: изменения откатываются, и до конца блока
 *       принимаются только {@code COMMIT} и {@code ROLLBACK}, остальное получает {@code 25P02}.
 *       {@code COMMIT} сломанной транзакции ничего не фиксирует и отвечает {@code ROLLBACK}.</li>
 * </ul>
 * Как и в PostgreSQL, {@code BEGIN} внутри транзакции и {@code COMMIT}/{@code ROLLBACK} вне её
 * ничего не делают (PostgreSQL при этом выдаёт предупреждение, у нас предупреждений пока нет).
 */
final class TransactionBlock {

    /** Сколько раз выполнять оператор вне BEGIN, пока он получает {@code 40001}. */
    static final int MAX_ATTEMPTS = 5;

    private final Catalog catalog;
    private final SessionTransaction current;
    private KvTransaction open;
    private boolean failed;

    TransactionBlock(Catalog catalog, SessionTransaction current) {
        this.catalog = catalog;
        this.current = current;
    }

    TransactionStatus status() {
        return failed ? TransactionStatus.FAILED
                : open != null ? TransactionStatus.IN_TRANSACTION
                : TransactionStatus.IDLE;
    }

    StatementResult execute(TransactionCommand command) {
        return switch (command) {
            case BEGIN -> {
                if (open == null && !failed) {
                    open = catalog.begin();
                    current.set(open);
                }
                yield new StatementResult.Update("BEGIN", 0);
            }
            case COMMIT -> {
                if (failed) {
                    failed = false;
                    yield new StatementResult.Update("ROLLBACK", 0);
                }
                if (open != null) {
                    try {
                        open.commit();
                    } finally {
                        end();
                    }
                }
                yield new StatementResult.Update("COMMIT", 0);
            }
            case ROLLBACK -> {
                failed = false;
                if (open != null) {
                    end(); // close без commit — это откат
                }
                yield new StatementResult.Update("ROLLBACK", 0);
            }
        };
    }

    /**
     * Любой оператор, кроме команд транзакции. Ошибка внутри блока ломает транзакцию:
     * её изменения сразу откатываются, а блокировки снимаются.
     */
    <T> T statement(Supplier<T> statement) {
        if (failed) {
            throw new SwdbException(SqlStates.IN_FAILED_SQL_TRANSACTION,
                    "current transaction is aborted, commands ignored until end of transaction block");
        }
        try {
            return statement.get();
        } catch (RuntimeException e) {
            if (open != null) {
                end();
                failed = true;
            }
            throw e;
        }
    }

    /**
     * Чтение или изменение данных: внутри блока — в его транзакции, иначе — в своей,
     * которая фиксируется, если оператор выполнился без ошибок.
     * <p>
     * Вне блока оператор при {@code 40001} выполняется ещё раз в новой транзакции со свежим снимком.
     * Так оператор, ждавший DDL или чужую запись той же строки, видит их результат, как в PostgreSQL
     * (там каждый оператор вне BEGIN получает новый снимок), а не просит клиента повторить.
     * Это безопасно: изменения неудачной попытки откатаны, а результат ещё не ушёл клиенту —
     * {@link StatementResult} собирается целиком до возврата. Внутри блока так нельзя:
     * повтор одного оператора не повторил бы предыдущие, и транзакция видела бы разные снимки.
     */
    <T> T withTransaction(Supplier<T> work) {
        if (open != null) {
            return work.get();
        }
        for (int attempt = 1; ; attempt++) {
            try (KvTransaction tx = catalog.begin()) {
                current.set(tx);
                T result = work.get();
                tx.commit();
                return result;
            } catch (SwdbException e) {
                if (!e.sqlState().equals(SqlStates.SERIALIZATION_FAILURE) || attempt == MAX_ATTEMPTS) {
                    throw e;
                }
            } finally {
                current.clear();
            }
        }
    }

    /** Сессия закрывается: незафиксированная транзакция откатывается. */
    void close() {
        if (open != null) {
            end();
        }
    }

    private void end() {
        try {
            open.close();
        } finally {
            open = null;
            current.clear();
        }
    }
}
