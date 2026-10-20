package io.github.roman6666861.swdb.sql;

import org.apache.calcite.rel.type.RelDataTypeSystemImpl;

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
}
