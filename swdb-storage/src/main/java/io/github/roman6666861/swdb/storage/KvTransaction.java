package io.github.roman6666861.swdb.storage;

import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDBException;
import org.rocksdb.Status;
import org.rocksdb.Transaction;
import org.rocksdb.TransactionDB;
import org.rocksdb.TransactionOptions;
import org.rocksdb.WriteOptions;

import java.time.Duration;

/**
 * Транзакция RocksDB с изоляцией снимка (snapshot isolation, в PostgreSQL — REPEATABLE READ).
 * <ul>
 *   <li>Чтения видят данные на момент {@link KvStore#begin()} плюс собственные изменения;
 *       чужие изменения, сделанные позже, не видны, даже после их COMMIT.</li>
 *   <li>Изменения копятся в буфере транзакции и попадают в базу одним атомарным пакетом
 *       при {@link #commit()}; {@link #rollback()} их просто выбрасывает.</li>
 *   <li>Запись ключа блокирует его до конца транзакции. Вторая транзакция, пишущая тот же ключ,
 *       ждёт. Если ключ изменили после нашего снимка, запись отклоняется: иначе изменение,
 *       которого мы не видели, было бы молча затёрто (потерянное обновление).</li>
 * </ul>
 * Не потокобезопасна: одна транзакция — один поток (сессия). Итераторы {@link #scan} нужно
 * закрыть до закрытия транзакции.
 */
public final class KvTransaction implements AutoCloseable {

    private final Transaction transaction;
    private final TransactionOptions options;
    private final RangeLockManager rangeLocks;
    private final Duration lockTimeout;
    private final RangeLockManager.Owner rangeOwner = new RangeLockManager.Owner();
    private boolean finished;

    KvTransaction(TransactionDB db, WriteOptions writeOptions, Duration lockTimeout, RangeLockManager rangeLocks) {
        this.rangeLocks = rangeLocks;
        this.lockTimeout = lockTimeout;
        this.options = new TransactionOptions()
                .setSetSnapshot(true)
                .setDeadlockDetect(true)
                .setLockTimeout(lockTimeout.toMillis());
        this.transaction = db.beginTransaction(writeOptions, options);
    }

    /**
     * Пытается захватить диапазон без ожидания. Отказ не завершает транзакцию.
     * Диапазоны освобождаются после успешного commit/rollback или при close;
     * откат к точке сохранения пока сохраняет их до конца транзакции.
     * Проверки изменений после снимка здесь пока нет. Обычные get/put/scan
     * автоматически диапазоны не захватывают.
     */
    public boolean tryLockRange(LockRange range, RangeLockManager.Mode mode) {
        if (finished) {
            throw new IllegalStateException("transaction is already finished");
        }
        return rangeLocks.tryAcquire(rangeOwner, range, mode);
    }

    /**
     * Захватывает диапазон с ожиданием не дольше тайм-аута, заданного при begin.
     * Тайм-аут и прерывание сообщаются как LOCK_NOT_AVAILABLE; сама транзакция
     * остаётся открытой, а уже захваченные диапазоны сохраняются.
     * Как и tryLockRange, пока не проверяет изменения после снимка.
     */
    public void lockRange(LockRange range, RangeLockManager.Mode mode) {
        if (finished) {
            throw new IllegalStateException("transaction is already finished");
        }
        try {
            if (!rangeLocks.acquire(rangeOwner, range, mode, lockTimeout)) {
                throw new SwdbException(SqlStates.LOCK_NOT_AVAILABLE,
                        "canceling statement due to range lock timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SwdbException(SqlStates.LOCK_NOT_AVAILABLE,
                    "range lock wait interrupted", e);
        }
    }

    /** Значение ключа по снимку транзакции с учётом собственных изменений. */
    public byte[] get(byte[] key) {
        try (ReadOptions read = snapshotRead()) {
            return transaction.get(read, key);
        } catch (RocksDBException e) {
            throw error("get", e);
        }
    }

    /**
     * Как {@link #get}, но ещё и блокирует ключ, даже если его нет. Так проверяют уникальность:
     * вторая транзакция с тем же новым ключом подождёт и после COMMIT первой увидит, что ключ занят.
     */
    public byte[] getForUpdate(byte[] key) {
        try (ReadOptions read = snapshotRead()) {
            return transaction.getForUpdate(read, key, true);
        } catch (RocksDBException e) {
            throw error("getForUpdate", e);
        }
    }

    /**
     * Как {@link #getForUpdate}, но блокировка <b>разделяемая</b> (как {@code SELECT ... FOR SHARE}
     * в PostgreSQL): её могут держать сразу несколько транзакций, а исключительная блокировка
     * ({@link #getForUpdate}, {@link #put}, {@link #delete}) ждёт, пока её не отпустят все.
     * Так же проверяет, что ключ не менялся после снимка.
     * <p>
     * Если транзакция держит разделяемую блокировку одна, её можно усилить до исключительной.
     */
    public byte[] getForShare(byte[] key) {
        try (ReadOptions read = snapshotRead()) {
            return transaction.getForUpdate(read, key, false);
        } catch (RocksDBException e) {
            throw error("getForShare", e);
        }
    }

    public void put(byte[] key, byte[] value) {
        try {
            transaction.put(key, value);
        } catch (RocksDBException e) {
            throw error("put", e);
        }
    }

    public void delete(byte[] key) {
        try {
            transaction.delete(key);
        } catch (RocksDBException e) {
            throw error("delete", e);
        }
    }

    /** Ключи из {@code [from, to)} по снимку транзакции вместе с собственными изменениями. */
    public KvIterator scan(byte[] from, byte[] to) {
        ReadOptions read = snapshotRead();
        return new KvIterator(transaction.getIterator(read), from, to, read);
    }

    /**
     * Ключи из {@code [from, to)} в <b>последнем зафиксированном</b> состоянии, а не по снимку,
     * вместе с собственными изменениями. Нужно, когда транзакция держит блокировку, исключающую
     * чужую запись в диапазон, но получила её позже своего снимка: строки, зафиксированные
     * за время ожидания, снимок бы не показал.
     */
    public KvIterator scanLatest(byte[] from, byte[] to) {
        ReadOptions read = new ReadOptions();
        return new KvIterator(transaction.getIterator(read), from, to, read);
    }

    /**
     * Берёт новый снимок: дальнейшие чтения видят всё, что зафиксировано к этому моменту,
     * а проверка конфликтов при записи сравнивает ключи уже с ним. Собственные изменения
     * транзакции остаются. На этом держится READ COMMITTED: свой снимок у каждого оператора.
     */
    public void refreshSnapshot() {
        transaction.setSnapshot();
    }

    /**
     * Запоминает состояние транзакции для {@link #rollbackToSavepoint}. Точек может быть
     * несколько; снять точку можно только откатом к ней.
     */
    public void setSavepoint() {
        try {
            transaction.setSavePoint();
        } catch (RocksDBException e) {
            throw error("setSavePoint", e);
        }
    }

    /** Отменяет изменения после последней {@link #setSavepoint точки сохранения} и снимает её. */
    public void rollbackToSavepoint() {
        try {
            transaction.rollbackToSavePoint();
        } catch (RocksDBException e) {
            throw error("rollbackToSavePoint", e);
        }
    }

    public void commit() {
        finish();
        try {
            transaction.commit();
            rangeLocks.releaseAll(rangeOwner);
        } catch (RocksDBException e) {
            throw error("commit", e);
        }
    }

    public void rollback() {
        finish();
        try {
            transaction.rollback();
            rangeLocks.releaseAll(rangeOwner);
        } catch (RocksDBException e) {
            throw error("rollback", e);
        }
    }

    /** Освобождает транзакцию; незафиксированные изменения откатываются. */
    @Override
    public void close() {
        try {
            if (!finished) {
                rollback();
            }
        } finally {
            try {
                try {
                    transaction.close();
                } finally {
                    options.close();
                }
            } finally {
                rangeLocks.releaseAll(rangeOwner);
            }
        }
    }

    private void finish() {
        if (finished) {
            throw new IllegalStateException("transaction is already finished");
        }
        finished = true;
    }

    private ReadOptions snapshotRead() {
        return new ReadOptions().setSnapshot(transaction.getSnapshot());
    }

    /** Ошибки блокировок и конфликтов — с кодами PostgreSQL: клиент может повторить транзакцию. */
    private static SwdbException error(String operation, RocksDBException e) {
        Status status = e.getStatus();
        Status.Code code = status == null ? null : status.getCode();
        Status.SubCode subCode = status == null ? null : status.getSubCode();
        if (code == Status.Code.Busy && subCode == Status.SubCode.Deadlock) {
            return new SwdbException(SqlStates.DEADLOCK_DETECTED, "deadlock detected", e);
        }
        if (code == Status.Code.Busy) {
            return new SwdbException(SqlStates.SERIALIZATION_FAILURE,
                    "could not serialize access due to concurrent update", e);
        }
        if (code == Status.Code.TimedOut) {
            return new SwdbException(SqlStates.LOCK_NOT_AVAILABLE, "canceling statement due to lock timeout", e);
        }
        return KvStore.storageError(operation + " failed", e);
    }
}
