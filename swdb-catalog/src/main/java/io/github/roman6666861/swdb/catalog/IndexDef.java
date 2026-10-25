package io.github.roman6666861.swdb.catalog;

import java.util.List;

/**
 * Описание вторичного индекса таблицы.
 *
 * @param id      номер индекса внутри таблицы: часть ключей его записей в хранилище
 * @param name    имя индекса
 * @param columns индексы колонок таблицы (в {@code TableDef.columns}) в порядке индекса
 */
public record IndexDef(int id, String name, List<Integer> columns) {

    public IndexDef {
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("index " + name + " has no columns");
        }
        columns = List.copyOf(columns);
    }
}
