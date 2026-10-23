package io.github.roman6666861.swdb.sql;

/**
 * Разбор текста SQL без парсера: где в нём строки, имена в кавычках и комментарии.
 * Нужен там, где текст правится до разбора Calcite ({@code PRIMARY KEY} в колонке, параметры {@code $1}).
 */
final class SqlText {

    private SqlText() {
    }

    /** Копия текста, где строки, имена в кавычках и комментарии заменены пробелами той же длины. */
    static String mask(String sql) {
        char[] chars = sql.toCharArray();
        int i = 0;
        while (i < chars.length) {
            int end;
            if (chars[i] == '\'' || chars[i] == '"') {
                end = quoteEnd(sql, i, chars[i]);
            } else if (sql.startsWith("--", i)) {
                end = sql.indexOf('\n', i);
                end = end < 0 ? chars.length : end;
            } else if (sql.startsWith("/*", i)) {
                end = sql.indexOf("*/", i + 2);
                end = end < 0 ? chars.length : end + 2;
            } else {
                i++;
                continue;
            }
            for (int j = i; j < end; j++) {
                chars[j] = ' ';
            }
            i = end;
        }
        return new String(chars);
    }

    /** Позиция после закрывающей кавычки; удвоенная кавычка внутри не закрывает. */
    private static int quoteEnd(String sql, int open, char quote) {
        int i = open + 1;
        while (i < sql.length()) {
            if (sql.charAt(i) == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return sql.length();
    }
}
