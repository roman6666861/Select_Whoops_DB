package io.github.roman6666861.swdb.storage;

import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.TransactionDB;
import org.rocksdb.TransactionDBOptions;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Упорядоченное key-value хранилище поверх RocksDB. Нижний слой СУБД:
 * таблицы, индексы и каталог кодируются в ключи этого хранилища.
 * <p>
 * Ключи сравниваются побайтово как беззнаковые числа, поэтому скан по префиксу
 * возвращает записи в порядке, заданном кодированием ключей (см. {@link KeyBuilder}).
 * <p>
 * База открывается как {@link TransactionDB}: кроме одиночных операций ниже, она умеет
 * транзакции с блокировками ключей ({@link #begin()}). Одиночные операции тоже берут
 * блокировки на время записи, поэтому не обходят транзакции, а ждут их.
 */
public final class KvStore implements AutoCloseable {

    static {
        RocksDB.loadLibrary();
    }

    /** Сколько транзакция ждёт чужую блокировку ключа; взаимные блокировки находятся сразу. */
    public static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofSeconds(10);

    private final Options options;
    private final TransactionDBOptions transactionOptions;
    private final WriteOptions writeOptions;
    private final TransactionDB db;

    private KvStore(Options options, TransactionDBOptions transactionOptions, TransactionDB db) {
        this.options = options;
        this.transactionOptions = transactionOptions;
        this.writeOptions = new WriteOptions();
        this.db = db;
    }

    public static KvStore open(Path dir) {
        // RocksDB создаёт только последний каталог пути, родительские — наша забота.
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new SwdbException(SqlStates.INTERNAL_ERROR, "cannot create data directory " + dir, e);
        }
        Options options = new Options().setCreateIfMissing(true);
        TransactionDBOptions transactionOptions = new TransactionDBOptions();
        try {
            return new KvStore(options, transactionOptions,
                    TransactionDB.open(options, transactionOptions, dir.toAbsolutePath().toString()));
        } catch (RocksDBException e) {
            transactionOptions.close();
            options.close();
            throw storageError("cannot open storage at " + dir, e);
        }
    }

    /** Новая транзакция с {@link #DEFAULT_LOCK_TIMEOUT ожиданием блокировок по умолчанию}. */
    public KvTransaction begin() {
        return begin(DEFAULT_LOCK_TIMEOUT);
    }

    /** Новая транзакция; её снимок данных берётся в момент вызова. Обязательно закрывать. */
    public KvTransaction begin(Duration lockTimeout) {
        return new KvTransaction(db, writeOptions, lockTimeout);
    }

    public byte[] get(byte[] key) {
        try {
            return db.get(key);
        } catch (RocksDBException e) {
            throw storageError("get failed", e);
        }
    }

    public void put(byte[] key, byte[] value) {
        try {
            db.put(key, value);
        } catch (RocksDBException e) {
            throw storageError("put failed", e);
        }
    }

    public void delete(byte[] key) {
        try {
            db.delete(key);
        } catch (RocksDBException e) {
            throw storageError("delete failed", e);
        }
    }

    /**
     * Атомарно применяет набор изменений: после сбоя на диске окажутся либо все, либо ни одного.
     */
    public void write(Consumer<Batch> changes) {
        try (WriteBatch batch = new WriteBatch()) {
            changes.accept(new Batch(batch));
            db.write(writeOptions, batch);
        } catch (RocksDBException e) {
            throw storageError("write failed", e);
        }
    }

    /**
     * Итератор по всем ключам, начинающимся с {@code prefix}, в порядке возрастания.
     * Видит согласованный снимок данных на момент создания. Обязательно закрывать.
     */
    public KvIterator scan(byte[] prefix) {
        return scan(prefix, KeyBuilder.prefixEnd(prefix));
    }

    /**
     * Итератор по ключам из диапазона {@code [from, to)}; {@code to == null} — до конца хранилища.
     * Видит согласованный снимок данных на момент создания. Обязательно закрывать.
     */
    public KvIterator scan(byte[] from, byte[] to) {
        return new KvIterator(db.newIterator(), from, to);
    }

    @Override
    public void close() {
        writeOptions.close();
        db.close();
        transactionOptions.close();
        options.close();
    }

    static SwdbException storageError(String message, RocksDBException e) {
        return new SwdbException(SqlStates.INTERNAL_ERROR, message + ": " + e.getMessage(), e);
    }

    /** Набор изменений для {@link #write(Consumer)}. */
    public static final class Batch {

        private final WriteBatch batch;

        private Batch(WriteBatch batch) {
            this.batch = batch;
        }

        public Batch put(byte[] key, byte[] value) {
            try {
                batch.put(key, value);
            } catch (RocksDBException e) {
                throw storageError("put failed", e);
            }
            return this;
        }

        public Batch delete(byte[] key) {
            try {
                batch.delete(key);
            } catch (RocksDBException e) {
                throw storageError("delete failed", e);
            }
            return this;
        }
    }
}
