package io.github.roman6666861.swdb.sql;

import java.util.List;

/**
 * Подготовленный оператор: разобран и спланирован один раз, выполняется сколько угодно раз
 * с разными значениями параметров {@code $1, $2, ...}. Создаётся {@link SqlSession#prepare}.
 */
public interface PreparedQuery extends AutoCloseable {

    /** Тип каждого параметра ({@link java.sql.Types}) по порядку номеров; Calcite выводит их сам. */
    List<Integer> parameterTypes();

    /** Колонки результата; пусто, если оператор не возвращает строк (INSERT, BEGIN, SET...). */
    List<Column> columns();

    /**
     * Выполняет оператор так же, как {@link SqlSession#execute}: в транзакции сессии.
     *
     * @param parameters значения в Java-типах, соответствующих {@link #parameterTypes()};
     *                   {@code null} — SQL NULL
     */
    StatementResult execute(List<Object> parameters);

    @Override
    void close();
}
