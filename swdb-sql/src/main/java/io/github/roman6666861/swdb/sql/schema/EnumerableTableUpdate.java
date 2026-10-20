package io.github.roman6666861.swdb.sql.schema;

import io.github.roman6666861.swdb.catalog.TableStore;
import org.apache.calcite.adapter.enumerable.EnumerableRel;
import org.apache.calcite.adapter.enumerable.EnumerableRelImplementor;
import org.apache.calcite.adapter.enumerable.JavaRowFormat;
import org.apache.calcite.adapter.enumerable.PhysType;
import org.apache.calcite.adapter.enumerable.PhysTypeImpl;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.linq4j.tree.BlockBuilder;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.linq4j.tree.Types;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.prepare.Prepare;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.TableModify;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.ModifiableTable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Исполнение UPDATE над нашей таблицей. Стандартный {@code EnumerableTableModify} Calcite умеет
 * только INSERT и DELETE, поэтому этот узел плана подставляет {@link TableModifyRule}.
 * <p>
 * Calcite строит для {@code UPDATE t SET v = v + 1 WHERE id = 1} такой план:
 * <pre>
 *   EnumerableTableUpdate(updateColumnList=[[v]], sourceExpressionList=[[$2]])
 *     EnumerableCalc(... все колонки строки, затем новые значения: id, v, v + 1)
 *       ...чтение строк с id = 1
 * </pre>
 * То есть вход уже содержит и старую строку, и вычисленные новые значения. Остаётся собрать
 * из них пары «было → стало» и отдать их {@link TableStore#update}.
 * <p>
 * Исполнитель Enumerable не интерпретирует план, а генерирует по нему Java-код и компилирует его.
 * {@link #implement} пишет в этот код один вызов {@link #execute}, поэтому метод и класс публичные:
 * сгенерированный класс лежит в другом пакете.
 */
public final class EnumerableTableUpdate extends TableModify implements EnumerableRel {

    private static final Method EXECUTE = requireNonNull(
            Types.lookupMethod(EnumerableTableUpdate.class, "execute",
                    ModifiableTable.class, Enumerable.class, int[].class, int[].class));

    EnumerableTableUpdate(RelOptCluster cluster, RelTraitSet traits, RelOptTable table,
                          Prepare.CatalogReader catalogReader, RelNode input,
                          List<String> updateColumnList, List<RexNode> sourceExpressionList) {
        super(cluster, traits, table, catalogReader, input, Operation.UPDATE,
                updateColumnList, sourceExpressionList, false);
    }

    @Override
    public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
        return new EnumerableTableUpdate(getCluster(), traitSet, getTable(), getCatalogReader(), sole(inputs),
                requireNonNull(getUpdateColumnList()), requireNonNull(getSourceExpressionList()));
    }

    @Override
    public Result implement(EnumerableRelImplementor implementor, Prefer pref) {
        BlockBuilder builder = new BlockBuilder();
        Result input = implementor.visitChild(this, 0, (EnumerableRel) getInput(), pref);
        Expression rows = builder.append("rows",
                input.physType.convertTo(builder.append("input", input.block), JavaRowFormat.ARRAY));
        Expression table = requireNonNull(getTable().getExpression(ModifiableTable.class));
        builder.add(Expressions.return_(null, Expressions.call(EXECUTE, table, rows,
                Expressions.constant(targetColumns()), Expressions.constant(sourceFields()))));
        PhysType physType = PhysTypeImpl.of(implementor.getTypeFactory(), getRowType(), JavaRowFormat.SCALAR);
        return implementor.result(physType, builder.toBlock());
    }

    /** Номера изменяемых колонок в строке таблицы. */
    private int[] targetColumns() {
        List<String> names = getTable().getRowType().getFieldNames();
        return requireNonNull(getUpdateColumnList()).stream().mapToInt(names::indexOf).toArray();
    }

    /** Номера полей входа, где лежат новые значения этих колонок. */
    private int[] sourceFields() {
        return requireNonNull(getSourceExpressionList()).stream()
                .mapToInt(source -> ((RexInputRef) source).getIndex())
                .toArray();
    }

    /**
     * Вызывается из сгенерированного кода.
     *
     * @param rows          строки входа: сначала все колонки строки таблицы, затем новые значения
     * @param targetColumns какие колонки строки меняются
     * @param sourceFields  где во входе лежит новое значение для каждой из них
     * @return одна строка с числом изменённых строк, как у INSERT и DELETE
     */
    public static Enumerable<Long> execute(ModifiableTable table, Enumerable<Object[]> rows,
                                           int[] targetColumns, int[] sourceFields) {
        SwdbTable swdbTable = (SwdbTable) table;
        TableStore store = swdbTable.store();
        int columnCount = store.table().columns().size();
        // Сначала читаем всё, потом пишем: иначе изменённая строка могла бы попасть в чтение повторно.
        List<TableStore.Change> changes = new ArrayList<>();
        try (Enumerator<Object[]> enumerator = rows.enumerator()) {
            while (enumerator.moveNext()) {
                Object[] row = enumerator.current();
                Object[] before = Arrays.copyOf(row, columnCount);
                Object[] after = before.clone();
                for (int i = 0; i < targetColumns.length; i++) {
                    after[targetColumns[i]] = row[sourceFields[i]];
                }
                changes.add(new TableStore.Change(before, after));
            }
        }
        return Linq4j.singletonEnumerable(store.update(swdbTable.transaction(), changes));
    }
}
