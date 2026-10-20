package io.github.roman6666861.swdb.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
import io.github.roman6666861.swdb.storage.KvIterator;
import io.github.roman6666861.swdb.storage.KvStore;
import io.github.roman6666861.swdb.storage.KvTransaction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Каталог таблиц. Описания хранятся в том же RocksDB (ключи {@code 'M' 'T' [id]}) в виде JSON
 * и целиком загружаются в память при старте.
 * <p>
 * DDL-операции сериализуются блокировкой каталога; чтение каталога не блокируется.
 */
public final class Catalog {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final KvStore store;
    private final Map<String, TableStore> tables = new ConcurrentHashMap<>();
    private int nextTableId = 1; // guarded by this

    public Catalog(KvStore store) {
        this.store = store;
        try (KvIterator it = store.scan(Keys.tableMetaPrefix())) {
            while (it.next()) {
                TableDef table = fromJson(it.value());
                tables.put(table.name(), new TableStore(table));
                nextTableId = Math.max(nextTableId, table.id() + 1);
            }
        }
    }

    public Optional<TableStore> table(String name) {
        return Optional.ofNullable(tables.get(name));
    }

    public List<TableStore> tables() {
        return List.copyOf(tables.values());
    }

    /** Транзакция для чтения и изменения строк таблиц. DDL выполняется вне транзакций. */
    public KvTransaction begin() {
        return store.begin();
    }

    /**
     * Создаёт таблицу. Колонки первичного ключа автоматически становятся NOT NULL.
     *
     * @return {@code false}, если таблица уже есть и задан {@code ifNotExists}
     */
    public synchronized boolean createTable(String name, List<ColumnDef> columns, List<String> primaryKey,
                                            boolean ifNotExists) {
        if (tables.containsKey(name)) {
            if (ifNotExists) {
                return false;
            }
            throw new SwdbException(SqlStates.DUPLICATE_TABLE, "relation \"" + name + "\" already exists");
        }
        TableDef table = new TableDef(nextTableId, name, columns, resolvePrimaryKey(name, columns, primaryKey));
        List<ColumnDef> withNotNullKey = new ArrayList<>(table.columns());
        for (int index : table.primaryKey()) {
            withNotNullKey.set(index, withNotNullKey.get(index).withNullable(false));
        }
        table = new TableDef(table.id(), name, withNotNullKey, table.primaryKey());

        store.put(Keys.tableMeta(table.id()), toJson(table));
        tables.put(name, new TableStore(table));
        nextTableId++;
        return true;
    }

    /**
     * Удаляет таблицу вместе со всеми данными одной атомарной записью.
     *
     * @return {@code false}, если таблицы нет и задан {@code ifExists}
     */
    public synchronized boolean dropTable(String name, boolean ifExists) {
        TableStore existing = tables.get(name);
        if (existing == null) {
            if (ifExists) {
                return false;
            }
            throw new SwdbException(SqlStates.UNDEFINED_TABLE, "table \"" + name + "\" does not exist");
        }
        int id = existing.table().id();
        // Удаления диапазона (deleteRange) TransactionDB не поддерживает: блокировки в нём
        // ставятся на отдельные ключи. Поэтому строки удаляются по одной, но одним пакетом.
        List<byte[]> rows = new ArrayList<>();
        try (KvIterator it = store.scan(Keys.dataPrefix(id))) {
            while (it.next()) {
                rows.add(it.key());
            }
        }
        store.write(batch -> {
            batch.delete(Keys.tableMeta(id));
            rows.forEach(batch::delete);
        });
        tables.remove(name);
        return true;
    }

    private static List<Integer> resolvePrimaryKey(String table, List<ColumnDef> columns, List<String> primaryKey) {
        if (columns.isEmpty()) {
            throw new SwdbException(SqlStates.INVALID_TABLE_DEFINITION, "table \"" + table + "\" must have columns");
        }
        Set<String> names = new HashSet<>();
        for (ColumnDef column : columns) {
            if (!names.add(column.name())) {
                throw new SwdbException(SqlStates.DUPLICATE_COLUMN,
                        "column \"" + column.name() + "\" specified more than once");
            }
        }
        if (primaryKey.isEmpty()) {
            throw new SwdbException(SqlStates.FEATURE_NOT_SUPPORTED,
                    "tables without PRIMARY KEY are not supported yet");
        }
        List<Integer> indexes = new ArrayList<>(primaryKey.size());
        for (String keyColumn : primaryKey) {
            int index = indexOf(columns, keyColumn);
            if (index < 0) {
                throw new SwdbException(SqlStates.UNDEFINED_COLUMN,
                        "column \"" + keyColumn + "\" named in key does not exist");
            }
            if (!columns.get(index).type().keyable()) {
                throw new SwdbException(SqlStates.FEATURE_NOT_SUPPORTED,
                        "type " + columns.get(index).type() + " is not supported in PRIMARY KEY yet");
            }
            if (indexes.contains(index)) {
                throw new SwdbException(SqlStates.DUPLICATE_COLUMN,
                        "column \"" + keyColumn + "\" appears twice in primary key constraint");
            }
            indexes.add(index);
        }
        return indexes;
    }

    private static int indexOf(List<ColumnDef> columns, String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private static byte[] toJson(TableDef table) {
        try {
            return JSON.writeValueAsBytes(table);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static TableDef fromJson(byte[] json) {
        try {
            return JSON.readValue(json, TableDef.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
