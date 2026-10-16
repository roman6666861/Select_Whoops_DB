package io.github.roman6666861.swdb.sql.schema;

import io.github.roman6666861.swdb.catalog.ColumnType;
import io.github.roman6666861.swdb.catalog.KeyBound;
import io.github.roman6666861.swdb.catalog.TableDef;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.sql.SqlKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Превращает условия из WHERE в диапазон первичного ключа, чтобы не читать всю таблицу.
 * <p>
 * Calcite передаёт в {@code FilterableTable.scan} список условий, соединённых через AND
 * (элемент списка сам может быть AND из нескольких условий). Элементы, полностью выполненные
 * выбором диапазона, удаляются из списка; оставшиеся Calcite проверит сам для каждой прочитанной строки.
 * Поэтому ошибиться можно только в сторону «прочитали лишнее», но не «потеряли нужное»:
 * если условие не распознано, оно просто остаётся.
 * <p>
 * Колонки первичного ключа разбираются слева направо: пока для колонки есть {@code =}, её значение
 * добавляется к префиксу ключа; на первой колонке без {@code =} можно взять диапазон
 * ({@code >}, {@code >=}, {@code <}, {@code <=}, BETWEEN), и на этом разбор заканчивается.
 * Для ключа {@code (d, seq)}:
 * <pre>
 *   d = 1 AND seq = 2        → одна строка (1, 2)
 *   d = 1                    → все строки с d = 1
 *   d = 1 AND seq > 5        → строки с d = 1 и seq > 5
 *   d > 1                    → строки с d > 1
 *   seq = 2                  → вся таблица: без d строки с seq = 2 разбросаны по всему ключу
 * </pre>
 */
final class KeyConditions {

    private KeyConditions() {
    }

    /** Граница для одной колонки: значение и включается ли оно. */
    private record Side(Object value, boolean inclusive) {
    }

    /** Что условие говорит про одну колонку; {@code null} — нет ограничения с этой стороны. */
    private record ColumnRange(Side lower, Side upper) {

        boolean isPoint() {
            return lower != null && upper != null && lower.inclusive() && upper.inclusive()
                    && lower.value().equals(upper.value());
        }

        /** Объединение ограничений по разным сторонам; пусто, если обе задают одну и ту же сторону. */
        Optional<ColumnRange> and(ColumnRange other) {
            if (lower != null && other.lower != null || upper != null && other.upper != null) {
                return Optional.empty();
            }
            return Optional.of(new ColumnRange(lower != null ? lower : other.lower,
                    upper != null ? upper : other.upper));
        }
    }

    /**
     * @param filters условия из WHERE; полностью выполненные диапазоном удаляются из списка
     * @param rex     нужен, чтобы раскрыть SEARCH (так Calcite записывает BETWEEN) в обычные сравнения
     */
    static KeyRange extract(TableDef table, List<RexNode> filters, RexBuilder rex) {
        // AND(a, AND(b, c)) → [a, b, c]: так каждое условие можно взять или оставить по отдельности.
        List<RexNode> conditions = new ArrayList<>();
        for (RexNode filter : filters) {
            conditions.addAll(RelOptUtil.conjunctions(filter));
        }

        List<Object> prefix = new ArrayList<>();
        List<RexNode> used = new ArrayList<>();
        ColumnRange range = new ColumnRange(null, null);
        for (int column : table.primaryKey()) {
            ColumnType type = table.columns().get(column).type();
            Optional<Object> equal = findEquality(conditions, column, type, rex, used);
            if (equal.isPresent()) {
                prefix.add(equal.get());
                continue;
            }
            if (rangeKeepsOrder(type)) {
                range = findRange(conditions, column, type, rex, used);
            }
            break; // дальше ключ упорядочен только внутри одного значения этой колонки
        }
        if (prefix.isEmpty() && range.lower() == null && range.upper() == null) {
            return KeyRange.ALL;
        }

        // Calcite разрешает только удалять элементы списка, но не заменять их частями. Поэтому
        // AND(id = 5, v < 100) остаётся целиком: ключ используется, а условие Calcite перепроверит.
        filters.removeIf(filter -> RelOptUtil.conjunctions(filter).stream()
                .allMatch(condition -> used.stream().anyMatch(u -> u == condition)));
        return new KeyRange(bound(prefix, range.lower()), bound(prefix, range.upper()));
    }

    private static Optional<Object> findEquality(List<RexNode> conditions, int column, ColumnType type,
                                                 RexBuilder rex, List<RexNode> used) {
        for (RexNode condition : conditions) {
            Optional<ColumnRange> range = columnRange(condition, column, type, rex);
            if (range.isPresent() && range.get().isPoint()) {
                used.add(condition);
                return Optional.of(range.get().lower().value());
            }
        }
        return Optional.empty();
    }

    /** Собирает нижнюю и верхнюю границы из условий; условие, повторяющее уже взятую сторону, остаётся Calcite. */
    private static ColumnRange findRange(List<RexNode> conditions, int column, ColumnType type,
                                         RexBuilder rex, List<RexNode> used) {
        ColumnRange result = new ColumnRange(null, null);
        for (RexNode condition : conditions) {
            Optional<ColumnRange> range = columnRange(condition, column, type, rex);
            if (range.isEmpty()) {
                continue;
            }
            Optional<ColumnRange> combined = result.and(range.get());
            if (combined.isPresent()) {
                result = combined.get();
                used.add(condition);
            }
        }
        return result;
    }

    /**
     * Граница ключа: префикс из равенств плюс граница диапазона. Если диапазона с этой стороны нет,
     * границей служит сам префикс (включительно) — то есть все строки с таким началом ключа.
     */
    private static KeyBound bound(List<Object> prefix, Side side) {
        if (side == null) {
            return prefix.isEmpty() ? null : new KeyBound(prefix, true);
        }
        List<Object> values = new ArrayList<>(prefix);
        values.add(side.value());
        return new KeyBound(values, side.inclusive());
    }

    /**
     * Диапазон по ключу верен, только если порядок ключей в хранилище совпадает с тем, как
     * сравнивает Calcite. Для строк это не так: ключ упорядочен по UTF-8 (по номерам символов),
     * а Calcite сравнивает {@code String.compareTo} по UTF-16, и для части символов порядок разный.
     */
    private static boolean rangeKeepsOrder(ColumnType type) {
        return switch (type) {
            case INTEGER, BIGINT, DOUBLE, DATE, TIMESTAMP -> true;
            case BOOLEAN, VARCHAR, DECIMAL -> false;
        };
    }

    /** Что условие говорит про колонку: сравнение с литералом или SEARCH, раскрытый в сравнения. */
    private static Optional<ColumnRange> columnRange(RexNode condition, int column, ColumnType type, RexBuilder rex) {
        if (condition.getKind() != SqlKind.SEARCH) {
            return comparison(condition, column, type);
        }
        // SEARCH($0, Sarg[[2..6]]) → AND(>=($0, 2), <=($0, 6)); несколько диапазонов (IN) дают OR — не берём.
        ColumnRange result = new ColumnRange(null, null);
        for (RexNode part : RelOptUtil.conjunctions(RexUtil.expandSearch(rex, null, condition))) {
            Optional<ColumnRange> range = comparison(part, column, type);
            if (range.isEmpty()) {
                return Optional.empty();
            }
            Optional<ColumnRange> combined = range.get().isPoint() ? range : result.and(range.get());
            if (combined.isEmpty()) {
                return Optional.empty();
            }
            result = combined.get();
        }
        return Optional.of(result);
    }

    /**
     * Сравнение вида {@code колонка <op> литерал} (или {@code литерал <op> колонка}).
     * <p>
     * Литерал должен быть того же SQL-типа, что и колонка. Calcite сам приводит литерал к типу
     * колонки, если это возможно без потерь; иначе (например, {@code id = 5.5} для INT) он
     * оборачивает в CAST колонку, и такое условие мы не берём.
     */
    private static Optional<ColumnRange> comparison(RexNode condition, int column, ColumnType type) {
        if (!(condition instanceof RexCall call) || call.getOperands().size() != 2) {
            return Optional.empty();
        }
        SqlKind kind = call.getKind();
        RexNode left = call.getOperands().get(0);
        RexNode right = call.getOperands().get(1);
        if (right instanceof RexInputRef) {
            // 5 < id — то же, что id > 5: меняем операнды местами и разворачиваем оператор.
            RexNode swap = left;
            left = right;
            right = swap;
            kind = kind.reverse();
        }
        if (!(left instanceof RexInputRef ref && ref.getIndex() == column
                && right instanceof RexLiteral literal
                && literal.getType().getSqlTypeName() == ref.getType().getSqlTypeName())) {
            return Optional.empty();
        }
        Object value = literalValue(literal, type);
        if (value == null) {
            return Optional.empty();
        }
        return switch (kind) {
            case EQUALS -> Optional.of(new ColumnRange(new Side(value, true), new Side(value, true)));
            case GREATER_THAN -> Optional.of(new ColumnRange(new Side(value, false), null));
            case GREATER_THAN_OR_EQUAL -> Optional.of(new ColumnRange(new Side(value, true), null));
            case LESS_THAN -> Optional.of(new ColumnRange(null, new Side(value, false)));
            case LESS_THAN_OR_EQUAL -> Optional.of(new ColumnRange(null, new Side(value, true)));
            default -> Optional.empty();
        };
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
