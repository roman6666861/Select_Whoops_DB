package io.github.roman6666861.swdb.catalog;

import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
import io.github.roman6666861.swdb.storage.KeyBuilder;
import io.github.roman6666861.swdb.storage.KvTransaction;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Данные одной таблицы: вставка, замена, удаление и чтение строк.
 * <p>
 * Каждая операция выполняется в транзакции {@link KvTransaction}: изменения видны другим только
 * после её COMMIT, а при ROLLBACK исчезают все вместе. Ключи, которые операция проверяет или
 * меняет, блокируются до конца транзакции ({@link KvTransaction#getForUpdate}), поэтому две
 * транзакции не могут одновременно вставить строку с одним первичным ключом.
 * <p>
 * Записи вторичных индексов меняются в той же транзакции, что и строки: индекс никогда не
 * расходится с таблицей, даже после ROLLBACK.
 */
public final class TableStore {

    private final TableDef table;
    private final RowCodec codec;

    TableStore(TableDef table) {
        this.table = table;
        this.codec = new RowCodec(table);
    }

    public TableDef table() {
        return table;
    }

    public void insert(KvTransaction tx, Object[] row) {
        Object[] normalized = codec.normalize(row);
        byte[] key = codec.encodeKey(normalized);
        if (tx.getForUpdate(key) != null) {
            throw duplicateKey();
        }
        tx.put(key, codec.encodeValue(normalized));
        for (IndexDef index : table.indexes()) {
            tx.put(codec.encodeIndexKey(index, normalized), key);
        }
    }

    /**
     * Удаляет строку с тем же первичным ключом; {@code false}, если такой строки нет.
     * Записи индексов ищутся по строке, прочитанной из хранилища, а не по {@code row}:
     * от {@code row} здесь нужен только первичный ключ.
     */
    public boolean delete(KvTransaction tx, Object[] row) {
        byte[] key = codec.encodeKey(codec.normalize(row));
        byte[] stored = tx.getForUpdate(key);
        if (stored == null) {
            return false;
        }
        tx.delete(key);
        Object[] old = codec.decodeValue(stored);
        for (IndexDef index : table.indexes()) {
            tx.delete(codec.encodeIndexKey(index, old));
        }
        return true;
    }

    /** Замена одной строки: {@code before} — как она лежит в таблице, {@code after} — какой станет. */
    public record Change(Object[] before, Object[] after) {
    }

    /**
     * Заменяет строки. Сначала проверяются все замены, и только потом что-то пишется:
     * ошибка в любой строке не оставляет в транзакции половину изменений.
     * <p>
     * Если меняется первичный ключ, строка переезжает: старый ключ удаляется, новый записывается.
     * Уникальность проверяется для итогового состояния, поэтому {@code id = id + 1} проходит,
     * хотя новый ключ одной строки совпадает со старым ключом другой. Строка, которой уже нет,
     * пропускается.
     * <p>
     * Запись индекса меняется, только если изменился её ключ: значение индексируемой колонки
     * или первичный ключ. Старые записи, как и в {@link #delete}, вычисляются по строке из хранилища.
     *
     * @return число заменённых строк
     */
    public long update(KvTransaction tx, List<Change> changes) {
        record Prepared(byte[] oldKey, Object[] before, byte[] newKey, Object[] after, byte[] value) {
            boolean moves() {
                return !Arrays.equals(oldKey, newKey);
            }
        }
        List<Prepared> prepared = new ArrayList<>(changes.size());
        for (Change change : changes) {
            Object[] after = codec.normalize(change.after());
            byte[] oldKey = codec.encodeKey(codec.normalize(change.before()));
            byte[] stored = tx.getForUpdate(oldKey);
            if (stored != null) {
                prepared.add(new Prepared(oldKey, codec.decodeValue(stored),
                        codec.encodeKey(after), after, codec.encodeValue(after)));
            }
        }
        // ByteBuffer сравнивается по содержимому, поэтому годится как ключ множества.
        Set<ByteBuffer> freed = new HashSet<>();
        for (Prepared p : prepared) {
            if (p.moves()) {
                freed.add(ByteBuffer.wrap(p.oldKey()));
            }
        }
        Set<ByteBuffer> taken = new HashSet<>();
        for (Prepared p : prepared) {
            ByteBuffer newKey = ByteBuffer.wrap(p.newKey());
            boolean occupied = p.moves() && !freed.contains(newKey) && tx.getForUpdate(p.newKey()) != null;
            if (!taken.add(newKey) || occupied) {
                throw duplicateKey();
            }
        }
        // Сначала все удаления, потом все записи: строка может занять ключ, освобождённый другой.
        prepared.stream().filter(Prepared::moves).forEach(p -> tx.delete(p.oldKey()));
        prepared.forEach(p -> tx.put(p.newKey(), p.value()));

        // С записями индексов так же: если две строки обмениваются первичными ключами при одинаковом
        // значении колонки, новая запись одной совпадает со старой записью другой.
        List<byte[]> stale = new ArrayList<>();
        List<byte[][]> fresh = new ArrayList<>();
        for (Prepared p : prepared) {
            for (IndexDef index : table.indexes()) {
                byte[] oldEntry = codec.encodeIndexKey(index, p.before());
                byte[] newEntry = codec.encodeIndexKey(index, p.after());
                if (!Arrays.equals(oldEntry, newEntry)) {
                    stale.add(oldEntry);
                    fresh.add(new byte[][]{newEntry, p.newKey()});
                }
            }
        }
        stale.forEach(tx::delete);
        fresh.forEach(entry -> tx.put(entry[0], entry[1]));
        return prepared.size();
    }

    /**
     * Строка по полному первичному ключу: одно чтение из хранилища, без перебора.
     *
     * @param key значения всех колонок первичного ключа в порядке их объявления в PRIMARY KEY
     */
    public Optional<Object[]> get(KvTransaction tx, Object... key) {
        if (key.length != table.primaryKey().size()) {
            throw new IllegalArgumentException("table " + table.name() + " has " + table.primaryKey().size()
                    + " primary key columns, got " + key.length + " values");
        }
        byte[] value = tx.get(codec.encodeKeyPrefix(Arrays.asList(key)));
        return Optional.ofNullable(value).map(codec::decodeValue);
    }

    /** Все строки таблицы в порядке первичного ключа. */
    public RowCursor scan(KvTransaction tx) {
        return scan(tx, null, null);
    }

    /**
     * Строки, первичный ключ которых лежит между {@code lower} и {@code upper}, в порядке ключа.
     * Граница {@code null} означает «без ограничения с этой стороны».
     * <p>
     * Так как ключи в хранилище отсортированы, это один последовательный проход по диапазону
     * {@code [from, to)} без чтения остальных строк. Включительность границ и границы-префиксы
     * сводятся к выбору {@code from}/{@code to} через {@link KeyBuilder#prefixEnd}:
     * <pre>
     *   lower: inclusive → key(lower)            exclusive → prefixEnd(key(lower))
     *   upper: inclusive → prefixEnd(key(upper)) exclusive → key(upper)
     * </pre>
     */
    public RowCursor scan(KvTransaction tx, KeyBound lower, KeyBound upper) {
        Function<KeyBound, byte[]> key = bound -> codec.encodeKeyPrefix(bound.values());
        byte[] prefix = Keys.dataPrefix(table.id());
        return new RowCursor(tx.scan(from(prefix, lower, key), to(prefix, upper, key)), codec);
    }

    /**
     * Строки, у которых значения колонок индекса лежат между {@code lower} и {@code upper},
     * в порядке индекса, а при равных значениях — в порядке первичного ключа. Границы — значения
     * первых колонок индекса и устроены так же, как в {@link #scan(KvTransaction, KeyBound, KeyBound)};
     * {@code null} в границе означает NULL (см. {@link KeyBound}).
     * <p>
     * Записи индекса проходятся одним последовательным сканом, а каждая строка читается по ключу
     * из значения записи. Скан и чтения идут по снимку одной транзакции, поэтому видят одно и то же.
     */
    public RowCursor scanIndex(KvTransaction tx, IndexDef index, KeyBound lower, KeyBound upper) {
        if (!table.indexes().contains(index)) {
            throw new IllegalArgumentException("table " + table.name() + " has no index " + index.name());
        }
        Function<KeyBound, byte[]> key = bound -> codec.encodeIndexPrefix(index, bound.values());
        byte[] prefix = Keys.indexPrefix(table.id(), index.id());
        return new RowCursor(tx.scan(from(prefix, lower, key), to(prefix, upper, key)), codec,
                rowKey -> rowOf(tx, rowKey));
    }

    private byte[] rowOf(KvTransaction tx, byte[] rowKey) {
        byte[] row = tx.get(rowKey);
        if (row == null) {
            // Индекс меняется в одной транзакции со строками, так что это ошибка в нашем коде.
            throw new SwdbException(SqlStates.INTERNAL_ERROR,
                    "index entry of table " + table.name() + " points to a missing row");
        }
        return row;
    }

    private static byte[] from(byte[] prefix, KeyBound lower, Function<KeyBound, byte[]> key) {
        return lower == null ? prefix : lower.inclusive() ? key.apply(lower) : KeyBuilder.prefixEnd(key.apply(lower));
    }

    private static byte[] to(byte[] prefix, KeyBound upper, Function<KeyBound, byte[]> key) {
        return upper == null ? KeyBuilder.prefixEnd(prefix)
                : upper.inclusive() ? KeyBuilder.prefixEnd(key.apply(upper)) : key.apply(upper);
    }

    private SwdbException duplicateKey() {
        return new SwdbException(SqlStates.UNIQUE_VIOLATION,
                "duplicate key value violates unique constraint \"" + table.name() + "_pkey\"");
    }
}
