package io.github.roman6666861.swdb.sql;

import java.util.List;

/**
 * Результат выполнения одного SQL-оператора.
 */
public sealed interface StatementResult {

    /** Запрос вернул набор строк. */
    record Rows(List<Column> columns, List<List<Object>> rows) implements StatementResult {
    }

    /** DML/DDL-оператор: {@code command} — первое ключевое слово (INSERT, UPDATE, ...). */
    record Update(String command, long count) implements StatementResult {
    }

    /** Пустая строка запроса. */
    record Empty() implements StatementResult {
    }
}
