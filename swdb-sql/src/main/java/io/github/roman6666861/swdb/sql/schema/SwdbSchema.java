package io.github.roman6666861.swdb.sql.schema;

import io.github.roman6666861.swdb.catalog.Catalog;
import io.github.roman6666861.swdb.catalog.TableStore;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Схема {@code public} для Calcite. Список таблиц каждый раз берётся из каталога,
 * поэтому таблица, созданная в одной сессии, сразу видна во всех остальных.
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
        return catalog.tables().stream()
                .collect(Collectors.toMap(t -> t.table().name(), this::toCalciteTable));
    }

    private Table toCalciteTable(TableStore store) {
        return new SwdbTable(store, transaction);
    }
}
