package io.github.roman6666861.swdb.sql.schema;

import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.adapter.enumerable.EnumerableTableModifyRule;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.TableModify;

import static java.util.Objects.requireNonNull;

/**
 * Замена стандартного правила Calcite, которое превращает логический {@code TableModify}
 * (INSERT, DELETE, UPDATE) в исполняемый узел плана.
 * <p>
 * INSERT и DELETE обрабатываются как раньше — стандартным {@code EnumerableTableModify}.
 * UPDATE над нашей таблицей становится {@link EnumerableTableUpdate}: стандартный узел
 * на UPDATE падает при исполнении.
 */
public final class TableModifyRule extends EnumerableTableModifyRule {

    public static final RelOptRule INSTANCE = DEFAULT_CONFIG
            .withRuleFactory(TableModifyRule::new)
            .withDescription("SwdbTableModifyRule")
            .as(Config.class)
            .toRule();

    private TableModifyRule(Config config) {
        super(config);
    }

    @Override
    public RelNode convert(RelNode rel) {
        TableModify modify = (TableModify) rel;
        if (!modify.isUpdate() || modify.getTable().unwrap(SwdbTable.class) == null) {
            return super.convert(rel);
        }
        RelTraitSet traits = modify.getTraitSet().replace(EnumerableConvention.INSTANCE);
        return new EnumerableTableUpdate(modify.getCluster(), traits, modify.getTable(),
                modify.getCatalogReader(), convert(modify.getInput(), traits),
                requireNonNull(modify.getUpdateColumnList()), requireNonNull(modify.getSourceExpressionList()));
    }
}
