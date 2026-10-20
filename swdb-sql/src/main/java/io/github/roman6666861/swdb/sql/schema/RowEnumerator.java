package io.github.roman6666861.swdb.sql.schema;

import io.github.roman6666861.swdb.catalog.RowCursor;
import io.github.roman6666861.swdb.catalog.TableStore;
import org.apache.calcite.linq4j.Enumerator;

import java.util.function.Function;

/**
 * Потоковое чтение таблицы для Calcite: строки читаются из RocksDB по одной,
 * без загрузки всей таблицы в память, через текущую транзакцию сессии.
 *
 * @param <T> во что Calcite ждёт превратить строку {@code Object[]} (см. {@code rowFormat})
 */
final class RowEnumerator<T> implements Enumerator<T> {

    private final TableStore store;
    private final SessionTransaction transaction;
    private final KeyRange range;
    private final Function<Object[], T> rowFormat;
    private RowCursor cursor;

    RowEnumerator(TableStore store, SessionTransaction transaction, KeyRange range, Function<Object[], T> rowFormat) {
        this.store = store;
        this.transaction = transaction;
        this.range = range;
        this.rowFormat = rowFormat;
    }

    @Override
    public T current() {
        return rowFormat.apply(cursor.row());
    }

    @Override
    public boolean moveNext() {
        if (cursor == null) {
            cursor = store.scan(transaction.current(), range.lower(), range.upper());
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
