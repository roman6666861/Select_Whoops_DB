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
