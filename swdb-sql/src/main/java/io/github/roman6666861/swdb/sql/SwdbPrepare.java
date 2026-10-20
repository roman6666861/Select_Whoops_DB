package io.github.roman6666861.swdb.sql;

import io.github.roman6666861.swdb.sql.schema.TableModifyRule;
import org.apache.calcite.adapter.enumerable.EnumerableRules;
import org.apache.calcite.jdbc.CalcitePrepare;
import org.apache.calcite.plan.RelOptCostFactory;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.prepare.CalcitePrepareImpl;

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
}
