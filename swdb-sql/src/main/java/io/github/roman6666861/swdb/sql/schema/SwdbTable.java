package io.github.roman6666861.swdb.sql.schema;

import io.github.roman6666861.swdb.catalog.ColumnDef;
import io.github.roman6666861.swdb.catalog.TableStore;
import org.apache.calcite.DataContext;
import org.apache.calcite.adapter.java.AbstractQueryableTable;
import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.linq4j.QueryProvider;
import org.apache.calcite.linq4j.Queryable;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.prepare.Prepare;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.TableModify;
import org.apache.calcite.rel.logical.LogicalTableModify;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.FilterableTable;
import org.apache.calcite.schema.ModifiableTable;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.impl.AbstractTableQueryable;

import java.util.Collection;
import java.util.List;

/**
 * Таблица СУБД глазами Calcite.
 * <p>
 * Чтение: без WHERE Calcite вызывает {@link #asQueryable}, с WHERE — {@link #scan(DataContext, List)};
 * в обоих случаях строки перебирает {@link RowEnumerator}.
 * Запись: для INSERT и DELETE Calcite строит план {@code TableModify}, который добавляет строки
 * в {@link #getModifiableCollection()} или удаляет их оттуда, а коллекция пишет в RocksDB.
 * UPDATE выполняет {@link EnumerableTableUpdate}.
 */
final class SwdbTable extends AbstractQueryableTable implements ModifiableTable, FilterableTable {

    private final TableStore store;

    SwdbTable(TableStore store) {
        super(Object[].class);
        this.store = store;
    }

    TableStore store() {
        return store;
    }

    @Override
    public RelDataType getRowType(RelDataTypeFactory typeFactory) {
        RelDataTypeFactory.Builder builder = typeFactory.builder();
        for (ColumnDef column : store.table().columns()) {
            builder.add(column.name(), TypeMapping.toRelType(typeFactory, column));
        }
        return builder.build();
    }

    /**
     * Чтение без условий. Строка — всегда {@code Object[]}: для таблицы, реализующей
     * {@link FilterableTable}, Calcite ждёт массив даже при одной колонке.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> Queryable<T> asQueryable(QueryProvider queryProvider, SchemaPlus schema, String tableName) {
        return new AbstractTableQueryable<>(queryProvider, schema, this, tableName) {
            @Override
            public Enumerator<T> enumerator() {
                return new RowEnumerator<>(store, KeyRange.ALL, row -> (T) row);
            }
        };
    }

    /**
     * Чтение с условиями из WHERE. Здесь строки всегда {@code Object[]}, даже из одной колонки.
     * Условия по первичному ключу превращаются в диапазон ключей, остальные проверит Calcite.
     */
    @Override
    public Enumerable<Object[]> scan(DataContext root, List<RexNode> filters) {
        KeyRange range = KeyConditions.extract(store.table(), filters, new RexBuilder(root.getTypeFactory()));
        return new AbstractEnumerable<>() {
            @Override
            public Enumerator<Object[]> enumerator() {
                return new RowEnumerator<>(store, range, row -> row);
            }
        };
    }

    @Override
    public Collection<Object> getModifiableCollection() {
        return new RowCollection(store);
    }

    @Override
    public TableModify toModificationRel(RelOptCluster cluster, RelOptTable table,
                                         Prepare.CatalogReader catalogReader, RelNode child,
                                         TableModify.Operation operation, List<String> updateColumnList,
                                         List<RexNode> sourceExpressionList, boolean flattened) {
        return LogicalTableModify.create(table, catalogReader, child, operation,
                updateColumnList, sourceExpressionList, flattened);
    }
}
