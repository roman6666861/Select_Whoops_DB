package io.github.roman6666861.swdb.storage;

import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Упорядоченное key-value хранилище поверх RocksDB. Нижний слой СУБД:
 * таблицы, индексы и каталог кодируются в ключи этого хранилища.
 */
public final class KvStore implements AutoCloseable {

    static {
        RocksDB.loadLibrary();
    }

    private final Options options;
    private final RocksDB db;

    private KvStore(Options options, RocksDB db) {
        this.options = options;
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

    @Override
    public void close() {
        db.close();
        options.close();
    }

    private static SwdbException storageError(String message, RocksDBException e) {
        return new SwdbException(SqlStates.INTERNAL_ERROR, message + ": " + e.getMessage(), e);
    }
}
