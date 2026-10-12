package io.github.roman6666861.swdb.storage;

import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Упорядоченное key-value хранилище поверх RocksDB. Нижний слой СУБД:
 * таблицы, индексы и каталог кодируются в ключи этого хранилища.
 * <p>
 * Ключи сравниваются побайтово как беззнаковые числа, поэтому скан по префиксу
 * возвращает записи в порядке, заданном кодированием ключей (см. {@link KeyBuilder}).
 */
public final class KvStore implements AutoCloseable {

    static {
        RocksDB.loadLibrary();
    }

    private final Options options;
    private final WriteOptions writeOptions;
    private final RocksDB db;

    private KvStore(Options options, RocksDB db) {
        this.options = options;
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
        try {
            return new KvStore(options, RocksDB.open(options, dir.toAbsolutePath().toString()));
        } catch (RocksDBException e) {
            options.close();
            throw storageError("cannot open storage at " + dir, e);
        }
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
        return new KvIterator(db.newIterator(), prefix);
    }

    @Override
    public void close() {
        writeOptions.close();
        db.close();
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

        /** Удаляет все ключи в диапазоне {@code [from, to)}. */
        public Batch deleteRange(byte[] from, byte[] to) {
            try {
                batch.deleteRange(from, to);
            } catch (RocksDBException e) {
                throw storageError("deleteRange failed", e);
            }
            return this;
        }
    }
}
