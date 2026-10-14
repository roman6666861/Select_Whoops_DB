package io.github.roman6666861.swdb.sql.schema;

import io.github.roman6666861.swdb.catalog.ColumnType;
import io.github.roman6666861.swdb.catalog.KeyBound;
import io.github.roman6666861.swdb.catalog.TableDef;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Превращает условия из WHERE в диапазон первичного ключа, чтобы не читать всю таблицу.
 * <p>
 * Calcite передаёт в {@code FilterableTable.scan} список условий, соединённых через AND
 * (элемент списка сам может быть AND из нескольких условий). Элементы, полностью выполненные
 * выбором диапазона, удаляются из списка; оставшиеся Calcite проверит сам для каждой прочитанной строки. Поэтому ошибиться можно только в сторону
 * «прочитали лишнее», но не «потеряли нужное»: если условие не распознано, оно просто остаётся.
 * <p>
 * Пока распознаётся только равенство по всем колонкам первичного ключа: {@code id = 5}
 * или {@code d = DATE '2026-10-10' AND seq = 7} для составного ключа.
 */
final class KeyConditions {

    private KeyConditions() {
    }

    /**
     * @param filters условия из WHERE; распознанные условия удаляются из списка
     */
    static KeyRange extract(TableDef table, List<RexNode> filters) {
        // AND(a, AND(b, c)) → [a, b, c]: так каждое условие можно взять или оставить по отдельности.
        List<RexNode> conditions = new ArrayList<>();
        for (RexNode filter : filters) {
            conditions.addAll(RelOptUtil.conjunctions(filter));
        }

        List<Integer> primaryKey = table.primaryKey();
        Object[] key = new Object[primaryKey.size()];
        List<RexNode> used = new ArrayList<>();
        for (int i = 0; i < primaryKey.size(); i++) {
            int column = primaryKey.get(i);
            ColumnType type = table.columns().get(column).type();
            for (RexNode condition : conditions) {
                Optional<Object> value = equalityValue(condition, column, type);
                if (value.isPresent()) {
                    key[i] = value.get();
                    used.add(condition);
                    break;
                }
            }
            if (key[i] == null) {
                return KeyRange.ALL; // одной из колонок ключа нет в условиях: читаем всю таблицу
            }
        }

        // Calcite разрешает только удалять элементы списка, но не заменять их частями. Поэтому
        // AND(id = 5, v < 100) остаётся целиком: ключ используется, а условие Calcite перепроверит.
        filters.removeIf(filter -> RelOptUtil.conjunctions(filter).stream()
                .allMatch(condition -> used.stream().anyMatch(u -> u == condition)));
        KeyBound bound = KeyBound.inclusive(key);
        return new KeyRange(bound, bound);
    }

    /**
     * Значение из условия вида {@code колонка = литерал} (или {@code литерал = колонка}).
     * <p>
     * Литерал должен быть того же SQL-типа, что и колонка. Calcite сам приводит литерал к типу
     * колонки, если это возможно без потерь; иначе (например, {@code id = 5.5} для INT) он
     * оборачивает в CAST колонку, и такое условие мы не берём.
     */
    private static Optional<Object> equalityValue(RexNode condition, int column, ColumnType type) {
        if (!(condition instanceof RexCall call) || call.getKind() != SqlKind.EQUALS) {
            return Optional.empty();
        }
        RexNode left = call.getOperands().get(0);
        RexNode right = call.getOperands().get(1);
        if (right instanceof RexInputRef) {
            RexNode swap = left;
            left = right;
            right = swap;
        }
        if (left instanceof RexInputRef ref && ref.getIndex() == column
                && right instanceof RexLiteral literal
                && literal.getType().getSqlTypeName() == ref.getType().getSqlTypeName()) {
            return Optional.ofNullable(literalValue(literal, type));
        }
        return Optional.empty();
    }

    /** Значение литерала во внутреннем представлении Calcite, в котором хранятся строки таблицы. */
    private static Object literalValue(RexLiteral literal, ColumnType type) {
        return switch (type) {
            case BOOLEAN -> literal.getValueAs(Boolean.class);
            case INTEGER, DATE -> literal.getValueAs(Integer.class); // DATE — дни от 1970-01-01
            case BIGINT, TIMESTAMP -> literal.getValueAs(Long.class); // TIMESTAMP — миллисекунды
            case DOUBLE -> literal.getValueAs(Double.class);
            case VARCHAR -> literal.getValueAs(String.class);
            case DECIMAL -> null; // DECIMAL не бывает в первичном ключе
        };
    }
}
