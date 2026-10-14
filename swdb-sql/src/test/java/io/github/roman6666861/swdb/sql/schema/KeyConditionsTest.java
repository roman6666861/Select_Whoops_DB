package io.github.roman6666861.swdb.sql.schema;

import io.github.roman6666861.swdb.catalog.ColumnDef;
import io.github.roman6666861.swdb.catalog.ColumnType;
import io.github.roman6666861.swdb.catalog.KeyBound;
import io.github.roman6666861.swdb.catalog.TableDef;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Разбор условий WHERE на уровне выражений Calcite ({@link RexNode}), без SQL и хранилища.
 */
class KeyConditionsTest {

    /** {@code t(id INT, v INT, PRIMARY KEY (id))}. */
    private static final TableDef T = new TableDef(1, "t", List.of(
            new ColumnDef("id", ColumnType.INTEGER, -1, -1, false),
            new ColumnDef("v", ColumnType.INTEGER, -1, -1, true)), List.of(0));

    /** {@code events(d INT, seq INT, note VARCHAR(10), PRIMARY KEY (d, seq))}. */
    private static final TableDef EVENTS = new TableDef(2, "events", List.of(
            new ColumnDef("d", ColumnType.INTEGER, -1, -1, false),
            new ColumnDef("seq", ColumnType.INTEGER, -1, -1, false),
            new ColumnDef("note", ColumnType.VARCHAR, 10, -1, true)), List.of(0, 1));

    private final RelDataTypeFactory types = new JavaTypeFactoryImpl();
    private final RexBuilder rex = new RexBuilder(types);

    @Test
    void equalityOnPrimaryKeyBecomesSingleKeyRange() {
        List<RexNode> filters = list(eq(intColumn(0), literal(5)));

        KeyRange range = KeyConditions.extract(T, filters);

        assertThat(range).isEqualTo(new KeyRange(KeyBound.inclusive(5), KeyBound.inclusive(5)));
        assertThat(filters).as("условие выполнено таблицей и больше не нужно Calcite").isEmpty();
    }

    @Test
    void literalOnTheLeftSide() {
        assertThat(KeyConditions.extract(T, list(eq(literal(5), intColumn(0)))))
                .isEqualTo(new KeyRange(KeyBound.inclusive(5), KeyBound.inclusive(5)));
    }

    @Test
    void otherConditionsStayForCalcite() {
        RexNode idEq = eq(intColumn(0), literal(5));
        RexNode vLess = rex.makeCall(SqlStdOperatorTable.LESS_THAN, intColumn(1), literal(100));
        List<RexNode> filters = list(idEq, vLess);

        KeyRange range = KeyConditions.extract(T, filters);

        assertThat(range.lower()).isEqualTo(KeyBound.inclusive(5));
        assertThat(filters).containsExactly(vLess);
    }

    @Test
    void andIsUsedForRangeButKeptWhole() {
        // Calcite запрещает возвращать условия, которых не было в списке, поэтому AND нельзя
        // разрезать и оставить только v < 100: он остаётся целиком, но ключ всё равно используется.
        RexNode and = rex.makeCall(SqlStdOperatorTable.AND, eq(intColumn(0), literal(5)),
                rex.makeCall(SqlStdOperatorTable.LESS_THAN, intColumn(1), literal(100)));
        List<RexNode> filters = list(and);

        assertThat(KeyConditions.extract(T, filters)).isEqualTo(new KeyRange(KeyBound.inclusive(5), KeyBound.inclusive(5)));
        assertThat(filters).containsExactly(and);
    }

    @Test
    void conditionWithoutPrimaryKeyReadsWholeTable() {
        RexNode vEq = eq(intColumn(1), literal(5));
        List<RexNode> filters = list(vEq);

        assertThat(KeyConditions.extract(T, filters)).isEqualTo(KeyRange.ALL);
        assertThat(filters).containsExactly(vEq);
    }

    @Test
    void castColumnIsNotUsed() {
        // Так Calcite записывает id = 5.5: к DECIMAL приводится колонка, а не литерал.
        RexNode cast = rex.makeCast(types.createSqlType(SqlTypeName.DECIMAL, 11, 1), intColumn(0));
        List<RexNode> filters = list(eq(cast, rex.makeExactLiteral(new BigDecimal("5.5"))));

        assertThat(KeyConditions.extract(T, filters)).isEqualTo(KeyRange.ALL);
        assertThat(filters).hasSize(1);
    }

    @Test
    void compositeKeyNeedsAllColumns() {
        RexNode d = eq(intColumn(0), literal(10));
        RexNode seq = eq(literal(2), intColumn(1));

        assertThat(KeyConditions.extract(EVENTS, list(seq, d)))
                .isEqualTo(new KeyRange(KeyBound.inclusive(10, 2), KeyBound.inclusive(10, 2)));

        List<RexNode> onlyD = list(d);
        assertThat(KeyConditions.extract(EVENTS, onlyD)).isEqualTo(KeyRange.ALL);
        assertThat(onlyD).containsExactly(d);
    }

    @Test
    void varcharKey() {
        TableDef tags = new TableDef(3, "tags", List.of(new ColumnDef("name", ColumnType.VARCHAR, 5, -1, false)), List.of(0));
        RelDataType varchar5 = types.createSqlType(SqlTypeName.VARCHAR, 5);
        RexNode column = rex.makeInputRef(varchar5, 0);
        // Строковый литерал имеет тип CHAR(3); Calcite в запросе приводит его к типу колонки.
        RexNode literal = rex.makeCast(varchar5, rex.makeLiteral("ann"));
        assertThat(literal).isInstanceOf(RexLiteral.class);

        assertThat(KeyConditions.extract(tags, list(eq(column, literal))))
                .isEqualTo(new KeyRange(KeyBound.inclusive("ann"), KeyBound.inclusive("ann")));
    }

    private RexNode intColumn(int index) {
        return rex.makeInputRef(types.createSqlType(SqlTypeName.INTEGER), index);
    }

    private RexNode literal(int value) {
        return rex.makeExactLiteral(BigDecimal.valueOf(value));
    }

    private RexNode eq(RexNode left, RexNode right) {
        return rex.makeCall(SqlStdOperatorTable.EQUALS, left, right);
    }

    /** Изменяемый список: KeyConditions удаляет из него выполненные условия. */
    private static List<RexNode> list(RexNode... nodes) {
        return new ArrayList<>(List.of(nodes));
    }
}
