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
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.apache.calcite.sql.fun.SqlStdOperatorTable.EQUALS;
import static org.apache.calcite.sql.fun.SqlStdOperatorTable.GREATER_THAN;
import static org.apache.calcite.sql.fun.SqlStdOperatorTable.GREATER_THAN_OR_EQUAL;
import static org.apache.calcite.sql.fun.SqlStdOperatorTable.LESS_THAN;
import static org.apache.calcite.sql.fun.SqlStdOperatorTable.LESS_THAN_OR_EQUAL;
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

    /** {@code tags(name VARCHAR(5), PRIMARY KEY (name))}. */
    private static final TableDef TAGS = new TableDef(3, "tags", List.of(
            new ColumnDef("name", ColumnType.VARCHAR, 5, -1, false)), List.of(0));

    private final RelDataTypeFactory types = new JavaTypeFactoryImpl();
    private final RexBuilder rex = new RexBuilder(types);

    // ---- равенство ----

    @Test
    void equalityOnPrimaryKeyBecomesSingleKeyRange() {
        List<RexNode> filters = list(call(EQUALS, column(0), literal(5)));

        KeyRange range = extract(T, filters);

        assertThat(range).isEqualTo(point(5));
        assertThat(filters).as("условие выполнено таблицей и больше не нужно Calcite").isEmpty();
    }

    @Test
    void literalOnTheLeftSide() {
        assertThat(extract(T, list(call(EQUALS, literal(5), column(0))))).isEqualTo(point(5));
    }

    @Test
    void otherConditionsStayForCalcite() {
        RexNode vLess = call(LESS_THAN, column(1), literal(100));
        List<RexNode> filters = list(call(EQUALS, column(0), literal(5)), vLess);

        assertThat(extract(T, filters)).isEqualTo(point(5));
        assertThat(filters).containsExactly(vLess);
    }

    @Test
    void andIsUsedForRangeButKeptWhole() {
        // Calcite запрещает возвращать условия, которых не было в списке, поэтому AND нельзя
        // разрезать и оставить только v < 100: он остаётся целиком, но ключ всё равно используется.
        RexNode and = call(SqlStdOperatorTable.AND, call(EQUALS, column(0), literal(5)),
                call(LESS_THAN, column(1), literal(100)));
        List<RexNode> filters = list(and);

        assertThat(extract(T, filters)).isEqualTo(point(5));
        assertThat(filters).containsExactly(and);
    }

    @Test
    void conditionWithoutPrimaryKeyReadsWholeTable() {
        RexNode vEq = call(EQUALS, column(1), literal(5));
        List<RexNode> filters = list(vEq);

        assertThat(extract(T, filters)).isEqualTo(KeyRange.ALL);
        assertThat(filters).containsExactly(vEq);
    }

    @Test
    void castColumnIsNotUsed() {
        // Так Calcite записывает id = 5.5: к DECIMAL приводится колонка, а не литерал.
        RexNode cast = rex.makeCast(types.createSqlType(SqlTypeName.DECIMAL, 11, 1), column(0));
        List<RexNode> filters = list(call(EQUALS, cast, rex.makeExactLiteral(new BigDecimal("5.5"))));

        assertThat(extract(T, filters)).isEqualTo(KeyRange.ALL);
        assertThat(filters).hasSize(1);
    }

    @Test
    void varcharEquality() {
        assertThat(extract(TAGS, list(call(EQUALS, varcharColumn(), varcharLiteral("ann")))))
                .isEqualTo(new KeyRange(KeyBound.inclusive("ann"), KeyBound.inclusive("ann")));
    }

    // ---- диапазоны ----

    @Test
    void comparisonsBecomeOneSidedRanges() {
        assertThat(extract(T, list(call(GREATER_THAN, column(0), literal(5)))))
                .isEqualTo(new KeyRange(KeyBound.exclusive(5), null));
        assertThat(extract(T, list(call(GREATER_THAN_OR_EQUAL, column(0), literal(5)))))
                .isEqualTo(new KeyRange(KeyBound.inclusive(5), null));
        assertThat(extract(T, list(call(LESS_THAN, column(0), literal(5)))))
                .isEqualTo(new KeyRange(null, KeyBound.exclusive(5)));
        assertThat(extract(T, list(call(LESS_THAN_OR_EQUAL, column(0), literal(5)))))
                .isEqualTo(new KeyRange(null, KeyBound.inclusive(5)));
        // 5 < id — то же, что id > 5.
        assertThat(extract(T, list(call(LESS_THAN, literal(5), column(0)))))
                .isEqualTo(new KeyRange(KeyBound.exclusive(5), null));
    }

    @Test
    void twoComparisonsMakeRange() {
        List<RexNode> filters = list(call(GREATER_THAN, column(0), literal(2)), call(LESS_THAN_OR_EQUAL, column(0), literal(6)));

        assertThat(extract(T, filters)).isEqualTo(new KeyRange(KeyBound.exclusive(2), KeyBound.inclusive(6)));
        assertThat(filters).isEmpty();
    }

    @Test
    void secondBoundOnSameSideStaysForCalcite() {
        RexNode gt2 = call(GREATER_THAN, column(0), literal(2));
        RexNode gt5 = call(GREATER_THAN, column(0), literal(5));
        List<RexNode> filters = list(gt2, gt5);

        assertThat(extract(T, filters)).isEqualTo(new KeyRange(KeyBound.exclusive(2), null));
        assertThat(filters).containsExactly(gt5);
    }

    @Test
    void betweenIsSearchWithOneRange() {
        // Так Calcite записывает BETWEEN 2 AND 6: SEARCH($0, Sarg[[2..6]]).
        RexNode between = rex.makeBetween(column(0), literal(2), literal(6));
        assertThat(between.getKind().name()).isEqualTo("SEARCH");
        List<RexNode> filters = list(between);

        assertThat(extract(T, filters)).isEqualTo(new KeyRange(KeyBound.inclusive(2), KeyBound.inclusive(6)));
        assertThat(filters).isEmpty();
    }

    @Test
    void inListIsNotUsed() {
        // IN (1, 7) — SEARCH из двух точек, раскрывается в OR: одним диапазоном его не прочитать.
        RexNode in = rex.makeIn(column(0), List.of(literal(1), literal(7)));
        List<RexNode> filters = list(in);

        assertThat(extract(T, filters)).isEqualTo(KeyRange.ALL);
        assertThat(filters).containsExactly(in);
    }

    @Test
    void varcharRangeIsNotUsed() {
        // Порядок ключей (UTF-8) и сравнение строк в Calcite (UTF-16) расходятся для части символов.
        List<RexNode> filters = list(call(GREATER_THAN, varcharColumn(), varcharLiteral("a")));

        assertThat(extract(TAGS, filters)).isEqualTo(KeyRange.ALL);
        assertThat(filters).hasSize(1);
    }

    // ---- составной ключ ----

    @Test
    void compositeKeyFullEquality() {
        assertThat(extract(EVENTS, list(call(EQUALS, literal(2), column(1)), call(EQUALS, column(0), literal(10)))))
                .isEqualTo(new KeyRange(KeyBound.inclusive(10, 2), KeyBound.inclusive(10, 2)));
    }

    @Test
    void compositeKeyPrefix() {
        List<RexNode> filters = list(call(EQUALS, column(0), literal(10)));

        assertThat(extract(EVENTS, filters)).isEqualTo(point(10));
        assertThat(filters).isEmpty();
    }

    @Test
    void compositeKeyPrefixAndRange() {
        List<RexNode> filters = list(call(EQUALS, column(0), literal(10)), call(GREATER_THAN, column(1), literal(5)));

        assertThat(extract(EVENTS, filters)).isEqualTo(new KeyRange(KeyBound.exclusive(10, 5), KeyBound.inclusive(10)));
        assertThat(filters).isEmpty();
    }

    @Test
    void rangeOnFirstColumnStopsAtIt() {
        RexNode seqEq = call(EQUALS, column(1), literal(5));
        List<RexNode> filters = list(call(GREATER_THAN, column(0), literal(10)), seqEq);

        assertThat(extract(EVENTS, filters)).isEqualTo(new KeyRange(KeyBound.exclusive(10), null));
        assertThat(filters).as("seq = 5 после диапазона по d ключом не ограничить").containsExactly(seqEq);
    }

    @Test
    void secondColumnAloneReadsWholeTable() {
        assertThat(extract(EVENTS, list(call(EQUALS, column(1), literal(5))))).isEqualTo(KeyRange.ALL);
    }

    private KeyRange extract(TableDef table, List<RexNode> filters) {
        return KeyConditions.extract(table, filters, rex);
    }

    private static KeyRange point(Object value) {
        return new KeyRange(KeyBound.inclusive(value), KeyBound.inclusive(value));
    }

    private RexNode column(int index) {
        return rex.makeInputRef(types.createSqlType(SqlTypeName.INTEGER), index);
    }

    private RexNode literal(int value) {
        return rex.makeExactLiteral(BigDecimal.valueOf(value));
    }

    private RexNode varcharColumn() {
        return rex.makeInputRef(varchar5(), 0);
    }

    /** Строковый литерал имеет тип CHAR(n); Calcite в запросе приводит его к типу колонки. */
    private RexNode varcharLiteral(String value) {
        RexNode literal = rex.makeCast(varchar5(), rex.makeLiteral(value));
        assertThat(literal).isInstanceOf(RexLiteral.class);
        return literal;
    }

    private RelDataType varchar5() {
        return types.createSqlType(SqlTypeName.VARCHAR, 5);
    }

    private RexNode call(SqlOperator operator, RexNode... operands) {
        return rex.makeCall(operator, operands);
    }

    /** Изменяемый список: KeyConditions удаляет из него выполненные условия. */
    private static List<RexNode> list(RexNode... nodes) {
        return new ArrayList<>(List.of(nodes));
    }
}
