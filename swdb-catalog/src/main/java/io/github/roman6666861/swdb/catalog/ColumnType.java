package io.github.roman6666861.swdb.catalog;

/**
 * Типы колонок, которые умеет хранить СУБД.
 * <p>
 * Значения в строках хранятся во внутреннем представлении Calcite:
 * DATE — число дней от 1970-01-01 ({@code Integer}), TIMESTAMP — миллисекунды от эпохи ({@code Long}).
 */
public enum ColumnType {
    BOOLEAN,
    INTEGER,
    BIGINT,
    DOUBLE,
    DECIMAL,
    VARCHAR,
    DATE,
    TIMESTAMP
}
