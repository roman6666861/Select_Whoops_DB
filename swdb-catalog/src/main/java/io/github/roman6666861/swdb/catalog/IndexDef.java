package io.github.roman6666861.swdb.catalog;

import java.util.List;

/**
 * Описание вторичного индекса таблицы.
 *
 * @param id      номер индекса внутри таблицы: часть ключей его записей в хранилище
 * @param name    имя индекса
 * @param columns индексы колонок таблицы (в {@code TableDef.columns}) в порядке индекса
 * @param unique  две строки не могут иметь одинаковые значения колонок индекса, если среди них
 *                нет NULL (NULL, как в PostgreSQL, не равен ничему, даже другому NULL)
 */
public record IndexDef(int id, String name, List<Integer> columns, boolean unique) {

    public IndexDef {
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("index " + name + " has no columns");
        }
        columns = List.copyOf(columns);
    }

    /** Неуникальный индекс. В описаниях, сохранённых до UNIQUE, поля нет, и Jackson подставляет false. */
    public IndexDef(int id, String name, List<Integer> columns) {
        this(id, name, columns, false);
    }
}
