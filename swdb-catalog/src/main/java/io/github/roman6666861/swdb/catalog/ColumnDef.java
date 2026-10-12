package io.github.roman6666861.swdb.catalog;

/**
 * Описание колонки.
 *
 * @param name      имя колонки
 * @param type      тип
 * @param precision длина VARCHAR или точность DECIMAL; -1 — не задана
 * @param scale     число знаков после запятой для DECIMAL; -1 — не задано
 * @param nullable  допускает ли колонка NULL
 */
public record ColumnDef(String name, ColumnType type, int precision, int scale, boolean nullable) {

    public static final int UNSPECIFIED = -1;

    public ColumnDef withNullable(boolean nullable) {
        return new ColumnDef(name, type, precision, scale, nullable);
    }
}
