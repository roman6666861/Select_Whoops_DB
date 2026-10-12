package io.github.roman6666861.swdb.catalog;

import java.util.List;

/**
 * Описание таблицы в каталоге.
 *
 * @param id         внутренний идентификатор: префикс ключей данных таблицы в хранилище
 * @param name       имя таблицы
 * @param columns    колонки в порядке объявления
 * @param primaryKey индексы колонок первичного ключа в {@code columns}
 */
public record TableDef(int id, String name, List<ColumnDef> columns, List<Integer> primaryKey) {

    public TableDef {
        columns = List.copyOf(columns);
        primaryKey = List.copyOf(primaryKey);
    }
}
