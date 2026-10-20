package io.github.roman6666861.swdb.sql;

import io.github.roman6666861.swdb.sql.schema.TableModifyRule;
import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.adapter.enumerable.EnumerableRel;
import org.apache.calcite.adapter.enumerable.EnumerableRules;
import org.apache.calcite.interpreter.BindableConvention;
import org.apache.calcite.jdbc.CalcitePrepare;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCostFactory;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.prepare.CalcitePrepareImpl;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.sql2rel.ConvertToChecked;
import org.apache.calcite.sql2rel.SqlRexConvertletTable;

import java.lang.reflect.Type;
import java.util.List;

/**
 * Подготовка запросов Calcite (разбор, проверка, оптимизация) с нашими правилами оптимизатора.
 * Подключается к драйверу через {@code Driver.withPrepareFactory}.
 */
final class SwdbPrepare extends CalcitePrepareImpl {

    // Context из пакета plan пишем полностью: внутри наследника CalcitePrepare простое имя
    // Context означает вложенный тип CalcitePrepare.Context.
    @Override
    protected RelOptPlanner createPlanner(CalcitePrepare.Context prepareContext,
                                          org.apache.calcite.plan.Context externalContext,
                                          RelOptCostFactory costFactory) {
        RelOptPlanner planner = super.createPlanner(prepareContext, externalContext, costFactory);
        // Стандартное правило не умеет UPDATE; наше делает то же самое и вдобавок UPDATE.
        planner.removeRule(EnumerableRules.ENUMERABLE_TABLE_MODIFICATION_RULE);
        planner.addRule(TableModifyRule.INSTANCE);
        return planner;
    }

    /** Повторяет {@code CalcitePrepareImpl.getPreparingStmt}, но создаёт {@link CheckedArithmeticStmt}. */
    @Override
    protected CalcitePreparingStmt getPreparingStmt(CalcitePrepare.Context context, Type elementType,
                                                    CalciteCatalogReader catalogReader, RelOptPlanner planner) {
        EnumerableRel.Prefer prefer = elementType == Object[].class
                ? EnumerableRel.Prefer.ARRAY
                : EnumerableRel.Prefer.CUSTOM;
        Convention resultConvention = enableBindable ? BindableConvention.INSTANCE : EnumerableConvention.INSTANCE;
        return new CheckedArithmeticStmt(this, context, catalogReader, context.getTypeFactory(),
                context.getRootSchema(), prefer, createCluster(planner, new RexBuilder(context.getTypeFactory())),
                resultConvention, createConvertletTable());
    }

    /**
     * Арифметика с проверкой переполнения, как в PostgreSQL: {@code 2147483647 + 1} для INT — ошибка,
     * а не {@code -2147483648}.
     * <p>
     * По умолчанию Calcite считает {@code + - * /} обычной арифметикой Java, которая молча
     * «заворачивается». Замену на проверяемые операторы ({@code ConvertToChecked}) он делает сам,
     * но только если этого требует уровень совместимости SQL, а для PostgreSQL такого уровня нет.
     * Поэтому применяем ту же замену сами, перед оптимизацией плана.
     */
    private static final class CheckedArithmeticStmt extends CalcitePreparingStmt {

        CheckedArithmeticStmt(CalcitePrepareImpl prepare, CalcitePrepare.Context context,
                              CalciteCatalogReader catalogReader, RelDataTypeFactory typeFactory,
                              CalciteSchema schema, EnumerableRel.Prefer prefer, RelOptCluster cluster,
                              Convention resultConvention, SqlRexConvertletTable convertletTable) {
            super(prepare, context, catalogReader, typeFactory, schema, prefer, cluster,
                    resultConvention, convertletTable);
        }

        @Override
        protected RelRoot optimize(RelRoot root, List<Materialization> materializations,
                                   List<CalciteSchema.LatticeEntry> lattices) {
            ConvertToChecked checked = new ConvertToChecked(rexBuilder, true);
            return super.optimize(root.withRel(checked.visit(root.rel)), materializations, lattices);
        }
    }
}
