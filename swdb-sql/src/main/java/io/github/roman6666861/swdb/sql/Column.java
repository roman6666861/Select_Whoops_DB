package io.github.roman6666861.swdb.sql;

/**
 * Колонка результата запроса.
 *
 * @param name     имя (метка) колонки
 * @param jdbcType тип из {@link java.sql.Types}
 */
public record Column(String name, int jdbcType) {
}
