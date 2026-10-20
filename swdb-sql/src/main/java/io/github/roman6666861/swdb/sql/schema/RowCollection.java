package io.github.roman6666861.swdb.sql.schema;

import io.github.roman6666861.swdb.catalog.RowCursor;
import io.github.roman6666861.swdb.catalog.TableStore;
import io.github.roman6666861.swdb.storage.KvTransaction;

import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * Коллекция-окно в таблицу, через которую Calcite выполняет INSERT и DELETE в транзакции
 * оператора: {@code add} вставляет строку, {@code removeAll} удаляет строки по первичному ключу.
 * <p>
 * Calcite считает число изменённых строк как разницу {@link #size()} до и после операции.
 * Настоящее число строк таблицы для этого не нужно (и его дорого считать: в каждой транзакции
 * оно своё), поэтому {@code size()} — это счётчик изменений через эту коллекцию: +1 за вставку,
 * −1 за удаление. Коллекция создаётся заново на каждый оператор, и разница получается верной.
 */
final class RowCollection extends AbstractCollection<Object> {

    private final TableStore store;
    private final KvTransaction tx;
    private int changes;

    RowCollection(TableStore store, KvTransaction tx) {
        this.store = store;
        this.tx = tx;
    }

    @Override
    public boolean add(Object row) {
        store.insert(tx, toRow(row));
        changes++;
        return true;
    }

    @Override
    public boolean removeAll(Collection<?> rows) {
        boolean changed = false;
        for (Object row : rows) {
            if (store.delete(tx, toRow(row))) {
                changes--;
                changed = true;
            }
        }
        return changed;
    }

    /** Счётчик изменений, а не число строк таблицы (см. описание класса). */
    @Override
    public int size() {
        return changes;
    }

    @Override
    public Iterator<Object> iterator() {
        List<Object> rows = new ArrayList<>();
        try (RowCursor cursor = store.scan(tx)) {
            while (cursor.next()) {
                rows.add(cursor.row());
            }
        }
        return rows.iterator();
    }

    /** У таблицы из одной колонки Calcite может передать значение без обёртки в массив. */
    private static Object[] toRow(Object row) {
        return row instanceof Object[] array ? array : new Object[]{row};
    }
}
