package io.github.roman6666861.swdb.sql;

import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Параметры в синтаксисе PostgreSQL ({@code $1, $2, ...}), переведённые в параметры Calcite ({@code ?}).
 * <p>
 * В PostgreSQL параметр — это номер: {@code $2} может стоять раньше {@code $1}, а {@code $1}
 * встречаться несколько раз. У Calcite (как и в JDBC) параметр — это позиция знака {@code ?}.
 * Поэтому запоминается, какой номер стоит на каждой позиции:
 * <pre>
 *   WHERE v = $2 OR id = $1 OR parent = $1
 *   WHERE v = ?  OR id = ?  OR parent = ?     order = [1, 0, 0]
 * </pre>
 *
 * @param sql   текст для Calcite
 * @param order для каждого {@code ?} по порядку — номер параметра, начиная с 0
 * @param count сколько параметров у оператора: наибольший номер {@code $n}
 */
record Placeholders(String sql, List<Integer> order, int count) {

    /** {@code $n}, но не часть имени вроде {@code a$1}: в PostgreSQL {@code $} допустим в идентификаторах. */
    private static final Pattern PARAMETER = Pattern.compile("(?<![\\w$])\\$(\\d+)");

    static Placeholders of(String text) {
        String mask = SqlText.mask(text);
        if (mask.indexOf('?') >= 0) {
            throw new SwdbException(SqlStates.SYNTAX_ERROR, "syntax error at or near \"?\": use $1, $2, ... for parameters");
        }
        StringBuilder sql = new StringBuilder(text.length());
        List<Integer> order = new ArrayList<>();
        int count = 0;
        int copied = 0;
        Matcher m = PARAMETER.matcher(mask);
        while (m.find()) {
            int number = Integer.parseInt(m.group(1));
            if (number < 1) {
                throw new SwdbException(SqlStates.UNDEFINED_PARAMETER, "there is no parameter $" + number);
            }
            sql.append(text, copied, m.start()).append('?');
            copied = m.end();
            order.add(number - 1);
            count = Math.max(count, number);
        }
        sql.append(text, copied, text.length());
        return new Placeholders(sql.toString(), List.copyOf(order), count);
    }
}
