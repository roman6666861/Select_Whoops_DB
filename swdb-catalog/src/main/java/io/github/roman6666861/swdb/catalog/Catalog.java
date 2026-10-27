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
        if (relationExists(name)) {
            if (ifNotExists) {
                return false;
            }
            throw relationAlreadyExists(name);
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
        List<byte[]> keys = keys(Keys.dataPrefix(id));
        for (IndexDef index : existing.table().indexes()) {
            keys.addAll(keys(Keys.indexPrefix(id, index.id())));
        }
        store.write(batch -> {
            batch.delete(Keys.tableMeta(id));
            keys.forEach(batch::delete);
        });
        tables.remove(name);
        return true;
    }

    /**
     * Создаёт индекс по колонкам таблицы и сразу заполняет его записями для уже существующих
     * строк. Новое описание таблицы и все записи пишутся одним пакетом: после сбоя не останется
     * индекса, заполненного наполовину.
     * <p>
     * Имена индексов и таблиц общие, как в PostgreSQL: индекс не может называться как таблица.
     *
     * @return {@code false}, если индекс или таблица с таким именем уже есть и задан {@code ifNotExists}
     */
    public synchronized boolean createIndex(String tableName, String name, List<String> columns,
                                            boolean ifNotExists) {
        if (relationExists(name)) {
            if (ifNotExists) {
                return false;
            }
            throw relationAlreadyExists(name);
        }
        TableStore existing = tables.get(tableName);
        if (existing == null) {
            throw new SwdbException(SqlStates.UNDEFINED_TABLE, "relation \"" + tableName + "\" does not exist");
        }
        TableDef table = existing.table();
        List<Integer> indexColumns = new ArrayList<>(columns.size());
        for (String column : columns) {
            int index = indexOf(table.columns(), column);
            if (index < 0) {
                throw new SwdbException(SqlStates.UNDEFINED_COLUMN, "column \"" + column + "\" does not exist");
            }
            if (!table.columns().get(index).type().keyable()) {
                throw new SwdbException(SqlStates.FEATURE_NOT_SUPPORTED,
                        "type " + table.columns().get(index).type() + " is not supported in indexes yet");
            }
            indexColumns.add(index);
        }
        // Записи удалённого индекса стираются вместе с его описанием, поэтому номер можно занять снова.
        int id = table.indexes().stream().mapToInt(IndexDef::id).max().orElse(0) + 1;
        IndexDef index = new IndexDef(id, name, indexColumns);
        TableDef withIndex = table.withIndex(index);
        RowCodec codec = new RowCodec(withIndex);

        store.write(batch -> {
            try (KvIterator it = store.scan(Keys.dataPrefix(table.id()))) {
                while (it.next()) {
                    batch.put(codec.encodeIndexKey(index, codec.decodeValue(it.value())), it.key());
                }
            }
            batch.put(Keys.tableMeta(table.id()), toJson(withIndex));
        });
        tables.put(tableName, new TableStore(withIndex));
        return true;
    }

    /**
     * Удаляет индекс вместе со всеми его записями одним пакетом.
     *
     * @return {@code false}, если индекса нет и задан {@code ifExists}
     */
    public synchronized boolean dropIndex(String name, boolean ifExists) {
        for (TableStore existing : tables.values()) {
            TableDef table = existing.table();
            for (IndexDef index : table.indexes()) {
                if (index.name().equals(name)) {
                    TableDef withoutIndex = table.withoutIndex(name);
                    List<byte[]> keys = keys(Keys.indexPrefix(table.id(), index.id()));
                    store.write(batch -> {
                        keys.forEach(batch::delete);
                        batch.put(Keys.tableMeta(table.id()), toJson(withoutIndex));
                    });
                    tables.put(table.name(), new TableStore(withoutIndex));
                    return true;
                }
            }
        }
        if (ifExists) {
            return false;
        }
        throw new SwdbException(SqlStates.UNDEFINED_OBJECT, "index \"" + name + "\" does not exist");
    }

    /** Есть ли таблица или индекс с таким именем: в PostgreSQL у них общее пространство имён. */
    public boolean relationExists(String name) {
        return tables.containsKey(name) || tables.values().stream()
                .flatMap(table -> table.table().indexes().stream())
                .anyMatch(index -> index.name().equals(name));
    }

    private static SwdbException relationAlreadyExists(String name) {
        return new SwdbException(SqlStates.DUPLICATE_TABLE, "relation \"" + name + "\" already exists");
    }

    /** Все ключи с этим префиксом. */
    private List<byte[]> keys(byte[] prefix) {
        List<byte[]> keys = new ArrayList<>();
        try (KvIterator it = store.scan(prefix)) {
            while (it.next()) {
                keys.add(it.key());
            }
        }
        return keys;
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
