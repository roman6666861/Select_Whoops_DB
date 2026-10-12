package io.github.roman6666861.swdb.sql.schema;

import io.github.roman6666861.swdb.catalog.ColumnDef;
import io.github.roman6666861.swdb.catalog.TableStore;
import org.apache.calcite.adapter.java.AbstractQueryableTable;
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
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.ModifiableTable;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.impl.AbstractTableQueryable;

import java.util.Collection;
import java.util.List;

/**
 * Таблица СУБД глазами Calcite.
 * <p>
 * Чтение: Calcite вызывает {@link #asQueryable} и перебирает строки через {@link RowEnumerator}.
 * Запись: для INSERT и DELETE Calcite строит план {@code TableModify}, который добавляет строки
 * в {@link #getModifiableCollection()} или удаляет их оттуда, а коллекция пишет в RocksDB.
 */
final class SwdbTable extends AbstractQueryableTable implements ModifiableTable {

    private final TableStore store;

    SwdbTable(TableStore store) {
        super(Object[].class);
        this.store = store;
    }

    @Override
    public RelDataType getRowType(RelDataTypeFactory typeFactory) {
        RelDataTypeFactory.Builder builder = typeFactory.builder();
        for (ColumnDef column : store.table().columns()) {
            builder.add(column.name(), TypeMapping.toRelType(typeFactory, column));
        }
        return builder.build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Queryable<T> asQueryable(QueryProvider queryProvider, SchemaPlus schema, String tableName) {
        return new AbstractTableQueryable<>(queryProvider, schema, this, tableName) {
            @Override
            public Enumerator<T> enumerator() {
                return (Enumerator<T>) new RowEnumerator(store);
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
