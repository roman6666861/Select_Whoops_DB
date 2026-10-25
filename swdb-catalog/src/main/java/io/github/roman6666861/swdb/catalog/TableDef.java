package io.github.roman6666861.swdb.catalog;

import java.util.ArrayList;
import java.util.List;

/**
 * Описание таблицы в каталоге.
 *
 * @param id         внутренний идентификатор: префикс ключей данных таблицы в хранилище
 * @param name       имя таблицы
 * @param columns    колонки в порядке объявления
 * @param primaryKey индексы колонок первичного ключа в {@code columns}
 * @param indexes    вторичные индексы
 */
public record TableDef(int id, String name, List<ColumnDef> columns, List<Integer> primaryKey,
                       List<IndexDef> indexes) {

    public TableDef {
        columns = List.copyOf(columns);
        primaryKey = List.copyOf(primaryKey);
        // В описаниях таблиц, сохранённых до появления индексов, этого поля нет: Jackson передаёт null.
        indexes = indexes == null ? List.of() : List.copyOf(indexes);
    }

    /** Таблица без вторичных индексов. */
    public TableDef(int id, String name, List<ColumnDef> columns, List<Integer> primaryKey) {
        this(id, name, columns, primaryKey, List.of());
    }

    /** Та же таблица с ещё одним индексом. */
    public TableDef withIndex(IndexDef index) {
        List<IndexDef> result = new ArrayList<>(indexes);
        result.add(index);
        return new TableDef(id, name, columns, primaryKey, result);
    }

    /** Та же таблица без индекса с этим именем. */
    public TableDef withoutIndex(String indexName) {
        return new TableDef(id, name, columns, primaryKey,
                indexes.stream().filter(index -> !index.name().equals(indexName)).toList());
    }
}
