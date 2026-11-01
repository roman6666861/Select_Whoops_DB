package io.github.roman6666861.swdb.sql.schema;

import io.github.roman6666861.swdb.catalog.Catalog;
import io.github.roman6666861.swdb.catalog.TableStore;
import io.github.roman6666861.swdb.storage.KvTransaction;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Схема {@code public} для Calcite. Список таблиц каждый раз берётся из каталога через текущую
 * транзакцию сессии: так оператор видит таблицы, созданные другими сессиями до его снимка,
 * и свои ещё не зафиксированные.
 */
public final class SwdbSchema extends AbstractSchema {

    public static final String NAME = "public";

    private final Catalog catalog;
    private final SessionTransaction transaction;

    public SwdbSchema(Catalog catalog, SessionTransaction transaction) {
        this.catalog = catalog;
        this.transaction = transaction;
    }

    @Override
    protected Map<String, Table> getTableMap() {
        KvTransaction current = transaction.currentOrNull();
        if (current != null) {
            return tableMap(current);
        }
        // Подготовка оператора вне BEGIN: транзакции ещё нет, каталог читаем в отдельной.
        try (KvTransaction read = catalog.begin()) {
            return tableMap(read);
        }
    }

    private Map<String, Table> tableMap(KvTransaction tx) {
        return catalog.tables(tx).stream()
                .collect(Collectors.toMap(t -> t.table().name(), this::toCalciteTable));
    }

    private Table toCalciteTable(TableStore store) {
        return new SwdbTable(catalog, store.table(), transaction);
    }
}
