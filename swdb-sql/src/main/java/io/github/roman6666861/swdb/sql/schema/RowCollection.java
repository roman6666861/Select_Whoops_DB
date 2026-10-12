package io.github.roman6666861.swdb.sql.schema;

import io.github.roman6666861.swdb.catalog.RowCursor;
import io.github.roman6666861.swdb.catalog.TableStore;

import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * Коллекция-окно в таблицу, через которую Calcite выполняет INSERT и DELETE:
 * {@code add} вставляет строку в RocksDB, {@code removeAll} удаляет строки по первичному ключу.
 * <p>
 * Calcite считает число изменённых строк как разницу {@link #size()} до и после операции,
 * поэтому размер должен быть точным.
 */
final class RowCollection extends AbstractCollection<Object> {

    private final TableStore store;

    RowCollection(TableStore store) {
        this.store = store;
    }

    @Override
    public boolean add(Object row) {
        store.insert(toRow(row));
        return true;
    }

    @Override
    public boolean removeAll(Collection<?> rows) {
        boolean changed = false;
        for (Object row : rows) {
            changed |= store.delete(toRow(row));
        }
        return changed;
    }

    @Override
    public int size() {
        return Math.toIntExact(store.rowCount());
    }

    @Override
    public Iterator<Object> iterator() {
        List<Object> rows = new ArrayList<>();
        try (RowCursor cursor = store.scan()) {
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
