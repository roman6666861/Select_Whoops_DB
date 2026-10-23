package io.github.roman6666861.swdb.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code PRIMARY KEY} в объявлении колонки: {@code CREATE TABLE t (id INT PRIMARY KEY, v INT)}.
 * <p>
 * Грамматика DDL в Calcite знает только ограничение таблицы {@code PRIMARY KEY (id)}, поэтому
 * текст переписывается до разбора: {@code CREATE TABLE t (id INT, v INT, PRIMARY KEY (id))}.
 * <p>
 * Список колонок разбирается посимвольно, а не регулярным выражением: запятые внутри
 * {@code DECIMAL(10, 2)}, строки в {@code '...'}, имена в {@code "..."} и комментарии не должны
 * сбивать разбор. Если колонка с {@code PRIMARY KEY} не одна, ограничений таблицы тоже получится
 * несколько, и {@code CREATE TABLE} отклонит их так же, как два {@code PRIMARY KEY (...)}.
 */
final class ColumnPrimaryKey {

    private static final Pattern CREATE_TABLE = Pattern.compile("(?is)\\s*create\\s+table\\b");
    private static final Pattern AS = Pattern.compile("(?i)\\bas\\b");
    private static final Pattern PRIMARY_KEY = Pattern.compile("(?i)\\bprimary\\s+key\\b");
    /** Элемент списка, который сам является ограничением таблицы, а не колонкой. */
    private static final Pattern TABLE_CONSTRAINT = Pattern.compile("(?is)\\s*(primary|constraint|unique|foreign|check)\\b.*");

    private ColumnPrimaryKey() {
    }

    /** Переписанный текст или тот же самый, если переписывать нечего. */
    static String toTableConstraint(String sql) {
        Matcher create = CREATE_TABLE.matcher(sql);
        if (!create.lookingAt()) {
            return sql;
        }
        // Всё, что внутри строк, имён в кавычках и комментариев, заменено пробелами:
        // по этой «маске» ищем скобки, запятые и ключевые слова, а текст берём из исходной строки.
        String mask = SqlText.mask(sql);
        int open = mask.indexOf('(', create.end());
        if (open < 0 || AS.matcher(mask.substring(create.end(), open)).find()) {
            return sql; // нет списка колонок или CREATE TABLE ... AS SELECT
        }
        List<int[]> elements = new ArrayList<>();
        int depth = 0;
        int start = open + 1;
        int close = -1;
        for (int i = start; i < mask.length() && close < 0; i++) {
            switch (mask.charAt(i)) {
                case '(' -> depth++;
                case ')' -> {
                    if (depth == 0) {
                        elements.add(new int[]{start, i});
                        close = i;
                    } else {
                        depth--;
                    }
                }
                case ',' -> {
                    if (depth == 0) {
                        elements.add(new int[]{start, i});
                        start = i + 1;
                    }
                }
                default -> {
                }
            }
        }
        if (close < 0) {
            return sql; // скобка не закрыта — пусть ошибку покажет парсер
        }

        List<String> columns = new ArrayList<>();
        List<String> keyColumns = new ArrayList<>();
        for (int[] element : elements) {
            String text = sql.substring(element[0], element[1]);
            String elementMask = mask.substring(element[0], element[1]);
            Matcher key = PRIMARY_KEY.matcher(elementMask);
            if (TABLE_CONSTRAINT.matcher(elementMask).matches() || !key.find()) {
                columns.add(text);
                continue;
            }
            keyColumns.add(columnName(text.strip()));
            columns.add(text.substring(0, key.start()) + text.substring(key.end()));
        }
        if (keyColumns.isEmpty()) {
            return sql;
        }
        StringBuilder result = new StringBuilder(sql.substring(0, open + 1)).append(String.join(",", columns));
        for (String column : keyColumns) {
            result.append(", PRIMARY KEY (").append(column).append(')');
        }
        return result.append(sql.substring(close)).toString();
    }

    /** Имя колонки — первое слово объявления, в кавычках или без. */
    private static String columnName(String declaration) {
        if (declaration.startsWith("\"")) {
            int end = declaration.indexOf('"', 1);
            while (end >= 0 && end + 1 < declaration.length() && declaration.charAt(end + 1) == '"') {
                end = declaration.indexOf('"', end + 2); // "" внутри имени — это одна кавычка
            }
            return declaration.substring(0, end + 1);
        }
        int end = 0;
        while (end < declaration.length() && !Character.isWhitespace(declaration.charAt(end))) {
            end++;
        }
        return declaration.substring(0, end);
    }
}
