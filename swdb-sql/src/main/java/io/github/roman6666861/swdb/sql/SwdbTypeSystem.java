package io.github.roman6666861.swdb.sql;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeSystemImpl;
import org.apache.calcite.sql.type.SqlTypeName;

import java.math.RoundingMode;

/**
 * Типовая система Calcite с поведением PostgreSQL там, где оно отличается от умолчаний.
 * Подключается свойством соединения {@code typeSystem}, поэтому класс и поле публичные.
 */
public final class SwdbTypeSystem extends RelDataTypeSystemImpl {

    public static final SwdbTypeSystem INSTANCE = new SwdbTypeSystem();

    private SwdbTypeSystem() {
    }

    /**
     * Как числа теряют дробную часть при приведении к INT или DECIMAL с меньшим масштабом.
     * По умолчанию в Calcite {@code DOWN} — отбросить: {@code CAST(2.7 AS INT) = 2}.
     * PostgreSQL округляет половину от нуля: 2.7 → 3, 1.005 → 1.01, -2.5 → -3.
     */
    @Override
    public RoundingMode roundingMode() {
        return RoundingMode.HALF_UP;
    }

    /** Сколько знаков после запятой у среднего, как в PostgreSQL: {@code avg(1, 2) = 1.5000000000000000}. */
    private static final int AVG_SCALE = 16;

    /**
     * Наибольшая точность DECIMAL. У Calcite по умолчанию 19, тогда у среднего с 16 знаками
     * после запятой на целую часть осталось бы 3 цифры. 38 — как в SQL Server, Spark и Hive;
     * в PostgreSQL {@code numeric} ещё шире.
     */
    @Override
    public int getMaxPrecision(SqlTypeName typeName) {
        return typeName == SqlTypeName.DECIMAL ? 38 : super.getMaxPrecision(typeName);
    }

    /**
     * Тип результата AVG. По умолчанию в Calcite он равен типу аргумента: среднее 1 и 2 — это 1.
     * В PostgreSQL среднее целых и DECIMAL — {@code numeric} с дробной частью: 1.5000000000000000.
     * <p>
     * Calcite считает AVG как SUM / COUNT, а DECIMAL делит с точностью 16 значащих цифр
     * ({@code MathContext.DECIMAL64} в {@code SqlFunctions.divide}). Поэтому у бесконечных дробей
     * последние знаки — нули: среднее 1, 2, 2 — 1.6666666666666670, а в PostgreSQL 1.6666666666666667.
     */
    @Override
    public RelDataType deriveAvgAggType(RelDataTypeFactory typeFactory, RelDataType argumentType) {
        int scale = switch (argumentType.getSqlTypeName()) {
            case TINYINT, SMALLINT, INTEGER, BIGINT -> AVG_SCALE;
            case DECIMAL -> Math.max(AVG_SCALE, argumentType.getScale());
            default -> -1;
        };
        if (scale < 0) {
            return super.deriveAvgAggType(typeFactory, argumentType);
        }
        RelDataType avgType = typeFactory.createSqlType(SqlTypeName.DECIMAL, getMaxPrecision(SqlTypeName.DECIMAL), scale);
        return typeFactory.createTypeWithNullability(avgType, argumentType.isNullable());
    }

    /**
     * Тип результата SUM. По умолчанию в Calcite он равен типу аргумента, и сумма INT
     * молча переполняется уже на двух больших значениях. В PostgreSQL {@code sum(int)} — {@code bigint}.
     * <p>
     * {@code sum(bigint)} в PostgreSQL — {@code numeric}, но так сделать нельзя: исполнитель
     * Calcite 1.42 для SUM с результатом DECIMAL генерирует {@code BigDecimal.add(long)},
     * которого нет, и запрос не компилируется. Поэтому сумма BIGINT остаётся BIGINT.
     */
    @Override
    public RelDataType deriveSumType(RelDataTypeFactory typeFactory, RelDataType argumentType) {
        return switch (argumentType.getSqlTypeName()) {
            case TINYINT, SMALLINT, INTEGER -> typeFactory.createTypeWithNullability(
                    typeFactory.createSqlType(SqlTypeName.BIGINT), argumentType.isNullable());
            default -> super.deriveSumType(typeFactory, argumentType);
        };
    }
}
