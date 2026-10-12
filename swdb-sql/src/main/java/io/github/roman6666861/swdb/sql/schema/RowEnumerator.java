package io.github.roman6666861.swdb.sql.schema;

import io.github.roman6666861.swdb.catalog.RowCursor;
import io.github.roman6666861.swdb.catalog.TableStore;
import org.apache.calcite.linq4j.Enumerator;

/**
 * Потоковое чтение таблицы для Calcite: строки читаются из RocksDB по одной,
 * без загрузки всей таблицы в память.
 * <p>
 * Формат строки зависит от числа колонок: у таблицы из одной колонки Calcite ждёт само значение
 * (формат SCALAR), у остальных — массив {@code Object[]} (формат ARRAY).
 */
final class RowEnumerator implements Enumerator<Object> {

    private final TableStore store;
    private final boolean scalar;
    private RowCursor cursor;

    RowEnumerator(TableStore store) {
        this.store = store;
        this.scalar = store.table().columns().size() == 1;
    }

    @Override
    public Object current() {
        Object[] row = cursor.row();
        return scalar ? row[0] : row;
    }

    @Override
    public boolean moveNext() {
        if (cursor == null) {
            cursor = store.scan();
        }
        return cursor.next();
    }

    @Override
    public void reset() {
        close();
    }

    @Override
    public void close() {
        if (cursor != null) {
            cursor.close();
            cursor = null;
        }
    }
}
