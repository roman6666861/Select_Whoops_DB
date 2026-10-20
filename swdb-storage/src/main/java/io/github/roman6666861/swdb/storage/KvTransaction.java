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
    private boolean finished;

    KvTransaction(TransactionDB db, WriteOptions writeOptions, Duration lockTimeout) {
        this.options = new TransactionOptions()
                .setSetSnapshot(true)
                .setDeadlockDetect(true)
                .setLockTimeout(lockTimeout.toMillis());
        this.transaction = db.beginTransaction(writeOptions, options);
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

    public void commit() {
        finish();
        try {
            transaction.commit();
        } catch (RocksDBException e) {
            throw error("commit", e);
        }
    }

    public void rollback() {
        finish();
        try {
            transaction.rollback();
        } catch (RocksDBException e) {
            throw error("rollback", e);
        }
    }

    /** Освобождает транзакцию; незафиксированные изменения откатываются. */
    @Override
    public void close() {
        if (!finished) {
            rollback();
        }
        transaction.close();
        options.close();
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
