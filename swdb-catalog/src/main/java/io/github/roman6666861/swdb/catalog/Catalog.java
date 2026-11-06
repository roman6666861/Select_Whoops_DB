package io.github.roman6666861.swdb.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
import io.github.roman6666861.swdb.storage.KeyBuilder;
import io.github.roman6666861.swdb.storage.KvIterator;
import io.github.roman6666861.swdb.storage.KvStore;
import io.github.roman6666861.swdb.storage.KvTransaction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Каталог таблиц и индексов. Описания хранятся в том же RocksDB (ключи {@code 'M' 'T' [id]})
 * в виде JSON и читаются и меняются через транзакцию, как обычные данные. Поэтому транзакция
 * видит каталог на момент своего снимка вместе со своими незафиксированными изменениями,
 * а DDL откатывается вместе с остальной транзакцией.
 * <p>
 * Каждый метод работает в транзакции, которую передал вызывающий: каталог сам транзакции
 * не начинает и не фиксирует. Так блокировки таблиц всегда держит транзакция оператора.
 */
public final class Catalog {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int TABLE = 'T';
    private static final int INDEX = 'I';
    /** Больше разных версий описаний кэш не держит: старые после DDL никому не нужны. */
    private static final int MAX_CACHED = 1000;

    private final KvStore store;
    /**
     * Номера таблиц, как OID в PostgreSQL, выдаются вне транзакций: после ROLLBACK номер
     * пропадает, зато двум одновременным транзакциям не достанется один и тот же.
     */
    private final AtomicInteger nextTableId = new AtomicInteger(1);
    /** Разобранные описания по их байтам: JSON разбирается один раз на версию описания. */
    private final Map<ByteBuffer, TableStore> stores = new ConcurrentHashMap<>();

    /** Что называется этим именем: таблица или индекс, и какой таблицы. */
    private record Relation(int kind, int tableId) {

        byte[] encode() {
            return new KeyBuilder().appendByte(kind).appendInt(tableId).build();
        }

        static Relation decode(byte[] value) {
            ByteBuffer buffer = ByteBuffer.wrap(value);
            return new Relation(buffer.get(), buffer.getInt() ^ Integer.MIN_VALUE);
        }
    }

    public Catalog(KvStore store) {
        this.store = store;
        List<TableDef> tables = new ArrayList<>();
        try (KvIterator it = store.scan(Keys.tableMetaPrefix())) {
            while (it.next()) {
                tables.add(fromJson(it.value()));
            }
        }
        tables.forEach(table -> nextTableId.accumulateAndGet(table.id() + 1, Math::max));
        // В базах, созданных до ключей имён, их нет: создаём по описаниям таблиц один раз.
        store.write(batch -> {
            for (TableDef table : tables) {
                if (store.get(Keys.relationName(table.name())) == null) {
                    batch.put(Keys.relationName(table.name()), new Relation(TABLE, table.id()).encode());
                    for (IndexDef index : table.indexes()) {
                        batch.put(Keys.relationName(index.name()), new Relation(INDEX, table.id()).encode());
                    }
                }
            }
        });
    }

    /** Транзакция для чтения и изменения строк таблиц и самого каталога. */
    public KvTransaction begin() {
        return store.begin();
    }

    /** Таблица, какой её видит транзакция. */
    public Optional<TableStore> table(KvTransaction tx, String name) {
        return relation(tx.get(Keys.relationName(name)))
                .filter(relation -> relation.kind() == TABLE)
                .map(relation -> storeOf(tx.get(Keys.tableMeta(relation.tableId()))));
    }

    /**
     * Таблица для оператора, который будет её читать или менять. Описание читается с
     * <b>разделяемой</b> блокировкой до конца транзакции, как {@code ACCESS SHARE} и
     * {@code ROW EXCLUSIVE} в PostgreSQL: такие операторы друг другу не мешают, а DDL этой таблицы
     * ждёт, пока транзакция не закончится. Если DDL успели зафиксировать после снимка транзакции,
     * описание по снимку устарело — тогда {@code 40001}, и транзакцию нужно повторить.
     */
    public Optional<TableStore> openTable(KvTransaction tx, String name) {
        return relation(tx.get(Keys.relationName(name)))
                .filter(relation -> relation.kind() == TABLE)
                .map(relation -> tx.getForShare(Keys.tableMeta(relation.tableId())))
                .map(this::storeOf);
    }

    /** Все таблицы, какими их видит транзакция. */
    public List<TableStore> tables(KvTransaction tx) {
        byte[] prefix = Keys.tableMetaPrefix();
        List<TableStore> result = new ArrayList<>();
        try (KvIterator it = tx.scan(prefix, KeyBuilder.prefixEnd(prefix))) {
            while (it.next()) {
                result.add(storeOf(it.value()));
            }
        }
        return result;
    }

    /**
     * Есть ли таблица или индекс с таким именем: в PostgreSQL у них общее пространство имён.
     * Имя, которое создаёт другая, ещё не зафиксированная транзакция, не видно.
     */
    public boolean relationExists(KvTransaction tx, String name) {
        return tx.get(Keys.relationName(name)) != null;
    }

    /**
     * Создаёт таблицу. Колонки первичного ключа автоматически становятся NOT NULL.
     *
     * @return {@code false}, если таблица уже есть и задан {@code ifNotExists}
     */
    public boolean createTable(KvTransaction tx, String name, List<ColumnDef> columns, List<String> primaryKey,
                               boolean ifNotExists) {
        List<Integer> key = resolvePrimaryKey(name, columns, primaryKey);
        byte[] nameKey = Keys.relationName(name);
        if (tx.getForUpdate(nameKey) != null) {
            if (ifNotExists) {
                return false;
            }
            throw relationAlreadyExists(name);
        }
        List<ColumnDef> withNotNullKey = new ArrayList<>(columns);
        for (int index : key) {
            withNotNullKey.set(index, withNotNullKey.get(index).withNullable(false));
        }
        TableDef table = new TableDef(nextTableId.getAndIncrement(), name, withNotNullKey, key);
        tx.put(Keys.tableMeta(table.id()), toJson(table));
        tx.put(nameKey, new Relation(TABLE, table.id()).encode());
        return true;
    }

    /**
     * Удаляет таблицу вместе со строками, индексами и их именами.
     *
     * @return {@code false}, если таблицы нет и задан {@code ifExists}
     */
    public boolean dropTable(KvTransaction tx, String name, boolean ifExists) {
        byte[] nameKey = Keys.relationName(name);
        Optional<Relation> relation = relation(tx.getForUpdate(nameKey)).filter(r -> r.kind() == TABLE);
        if (relation.isEmpty()) {
            if (ifExists) {
                return false;
            }
            throw new SwdbException(SqlStates.UNDEFINED_TABLE, "table \"" + name + "\" does not exist");
        }
        TableDef table = lockExclusive(tx, relation.get().tableId());
        // Удаления диапазона (deleteRange) в транзакции нет: строки удаляются по одной.
        deleteAll(tx, Keys.dataPrefix(table.id()));
        for (IndexDef index : table.indexes()) {
            deleteAll(tx, Keys.indexPrefix(table.id(), index.id()));
            tx.delete(Keys.relationName(index.name()));
        }
        tx.delete(Keys.serializableLock(table.id()));
        tx.delete(Keys.tableMeta(table.id()));
        tx.delete(nameKey);
        return true;
    }

    /**
     * Создаёт индекс по колонкам таблицы и сразу заполняет его записями для уже существующих
     * строк. Индекс может быть уникальным: если в таблице уже есть строки с одинаковыми
     * значениями, он не создаётся.
     * <p>
     * Имена индексов и таблиц общие, как в PostgreSQL: индекс не может называться как таблица.
     *
     * @return {@code false}, если индекс или таблица с таким именем уже есть и задан {@code ifNotExists}
     */
    public boolean createIndex(KvTransaction tx, String tableName, String name, List<String> columns,
                               boolean unique, boolean ifNotExists) {
        byte[] nameKey = Keys.relationName(name);
        if (tx.getForUpdate(nameKey) != null) {
            if (ifNotExists) {
                return false;
            }
            throw relationAlreadyExists(name);
        }
        TableDef table = lockExclusive(tx, table(tx, tableName)
                .orElseThrow(() -> new SwdbException(SqlStates.UNDEFINED_TABLE,
                        "relation \"" + tableName + "\" does not exist"))
                .table().id());
        List<Integer> indexColumns = new ArrayList<>(columns.size());
        for (String column : columns) {
            int index = indexOf(table.columns(), column);
            if (index < 0) {
                throw new SwdbException(SqlStates.UNDEFINED_COLUMN, "column \"" + column + "\" does not exist");
            }
            indexColumns.add(index);
        }
        // Записи удалённого индекса стираются вместе с его описанием, поэтому номер можно занять снова.
        int id = table.indexes().stream().mapToInt(IndexDef::id).max().orElse(0) + 1;
        IndexDef index = new IndexDef(id, name, indexColumns, unique);
        TableDef withIndex = table.withIndex(index);
        RowCodec codec = new RowCodec(withIndex);

        // Ключи уникальных записей: совпадение — две строки с одинаковыми значениями.
        Set<ByteBuffer> uniqueEntries = new HashSet<>();
        byte[] rows = Keys.dataPrefix(table.id());
        try (KvIterator it = tx.scanLatest(rows, KeyBuilder.prefixEnd(rows))) {
            while (it.next()) {
                Object[] row = codec.decodeValue(it.value());
                byte[] entry = codec.encodeIndexKey(index, row);
                if (codec.isUniqueEntry(index, row) && !uniqueEntries.add(ByteBuffer.wrap(entry))) {
                    // Записи уже в транзакции, но её откатит тот, кто получит исключение.
                    throw new SwdbException(SqlStates.UNIQUE_VIOLATION,
                            "could not create unique index \"" + name + "\"");
                }
                tx.put(entry, it.key());
            }
        }
        tx.put(Keys.tableMeta(table.id()), toJson(withIndex));
        tx.put(nameKey, new Relation(INDEX, table.id()).encode());
        return true;
    }

    /**
     * Удаляет индекс вместе со всеми его записями.
     *
     * @return {@code false}, если индекса нет и задан {@code ifExists}
     */
    public boolean dropIndex(KvTransaction tx, String name, boolean ifExists) {
        byte[] nameKey = Keys.relationName(name);
        Optional<Relation> relation = relation(tx.getForUpdate(nameKey)).filter(r -> r.kind() == INDEX);
        if (relation.isEmpty()) {
            if (ifExists) {
                return false;
            }
            throw new SwdbException(SqlStates.UNDEFINED_OBJECT, "index \"" + name + "\" does not exist");
        }
        TableDef table = lockExclusive(tx, relation.get().tableId());
        IndexDef index = table.indexes().stream().filter(i -> i.name().equals(name)).findFirst().orElseThrow();
        deleteAll(tx, Keys.indexPrefix(table.id(), index.id()));
        tx.put(Keys.tableMeta(table.id()), toJson(table.withoutIndex(name)));
        tx.delete(nameKey);
        return true;
    }

    private static Optional<Relation> relation(byte[] value) {
        return Optional.ofNullable(value).map(Relation::decode);
    }

    private TableStore storeOf(byte[] json) {
        if (stores.size() > MAX_CACHED) {
            stores.clear();
        }
        return stores.computeIfAbsent(ByteBuffer.wrap(json), key -> new TableStore(fromJson(json)));
    }

    private static SwdbException relationAlreadyExists(String name) {
        return new SwdbException(SqlStates.DUPLICATE_TABLE, "relation \"" + name + "\" already exists");
    }

    /**
     * Исключительная блокировка таблицы для DDL, как {@code ACCESS EXCLUSIVE} в PostgreSQL: ждёт,
     * пока все транзакции, работающие с таблицей ({@link #openTable}), не закончатся, и не пускает
     * новые до конца этой транзакции.
     * <p>
     * Блокировка может достаться позже снимка транзакции, а пока она ждала, другие успели
     * зафиксировать строки. Поэтому DDL под этой блокировкой читает строки и записи индексов
     * не по снимку, а в последнем состоянии ({@link KvTransaction#scanLatest}): писать в таблицу
     * сейчас всё равно никто не может.
     *
     * @return описание таблицы
     */
    private TableDef lockExclusive(KvTransaction tx, int tableId) {
        return storeOf(tx.getForUpdate(Keys.tableMeta(tableId))).table();
    }

    /**
     * Удаляет все ключи с этим префиксом (под {@link #lockExclusive}: в последнем состоянии).
     * Сначала собирает, чтобы не менять диапазон во время прохода.
     */
    private static void deleteAll(KvTransaction tx, byte[] prefix) {
        List<byte[]> keys = new ArrayList<>();
        try (KvIterator it = tx.scanLatest(prefix, KeyBuilder.prefixEnd(prefix))) {
            while (it.next()) {
                keys.add(it.key());
            }
        }
        keys.forEach(tx::delete);
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
