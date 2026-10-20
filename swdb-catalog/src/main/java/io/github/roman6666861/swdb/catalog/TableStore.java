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

/**
 * Данные одной таблицы: вставка, замена, удаление и чтение строк.
 * <p>
 * Каждая операция выполняется в транзакции {@link KvTransaction}: изменения видны другим только
 * после её COMMIT, а при ROLLBACK исчезают все вместе. Ключи, которые операция проверяет или
 * меняет, блокируются до конца транзакции ({@link KvTransaction#getForUpdate}), поэтому две
 * транзакции не могут одновременно вставить строку с одним первичным ключом.
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
    }

    /** Удаляет строку с тем же первичным ключом; {@code false}, если такой строки нет. */
    public boolean delete(KvTransaction tx, Object[] row) {
        byte[] key = codec.encodeKey(codec.normalize(row));
        if (tx.getForUpdate(key) == null) {
            return false;
        }
        tx.delete(key);
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
     *
     * @return число заменённых строк
     */
    public long update(KvTransaction tx, List<Change> changes) {
        record Prepared(byte[] oldKey, byte[] newKey, byte[] value) {
            boolean moves() {
                return !Arrays.equals(oldKey, newKey);
            }
        }
        List<Prepared> prepared = new ArrayList<>(changes.size());
        for (Change change : changes) {
            Object[] after = codec.normalize(change.after());
            prepared.add(new Prepared(codec.encodeKey(codec.normalize(change.before())),
                    codec.encodeKey(after), codec.encodeValue(after)));
        }
        prepared.removeIf(p -> tx.getForUpdate(p.oldKey()) == null);
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
        byte[] from = lower == null ? Keys.dataPrefix(table.id())
                : lower.inclusive() ? key(lower) : KeyBuilder.prefixEnd(key(lower));
        byte[] to = upper == null ? KeyBuilder.prefixEnd(Keys.dataPrefix(table.id()))
                : upper.inclusive() ? KeyBuilder.prefixEnd(key(upper)) : key(upper);
        return new RowCursor(tx.scan(from, to), codec);
    }

    private SwdbException duplicateKey() {
        return new SwdbException(SqlStates.UNIQUE_VIOLATION,
                "duplicate key value violates unique constraint \"" + table.name() + "_pkey\"");
    }

    private byte[] key(KeyBound bound) {
        return codec.encodeKeyPrefix(bound.values());
    }
}
