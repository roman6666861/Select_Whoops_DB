package io.github.roman6666861.swdb.sql;

import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Команды управления транзакцией в синтаксисе PostgreSQL. Грамматика Calcite их не знает,
 * поэтому они распознаются по тексту до разбора.
 */
enum TransactionCommand {

    BEGIN, COMMIT, ROLLBACK;

    /** {@code BEGIN [WORK | TRANSACTION] [параметры]} или {@code START TRANSACTION [параметры]}. */
    private static final Pattern BEGIN_PATTERN =
            Pattern.compile("(?is)(?:begin(?:\\s+(?:work|transaction))?|start\\s+transaction)(?:\\s+(.*))?");
    private static final Pattern COMMIT_PATTERN = Pattern.compile("(?is)(?:commit|end)(?:\\s+(?:work|transaction))?");
    private static final Pattern ROLLBACK_PATTERN = Pattern.compile("(?is)(?:rollback|abort)(?:\\s+(?:work|transaction))?");
    /** Единственный уровень, который у нас есть (изоляция снимка RocksDB). */
    private static final Pattern REPEATABLE_READ = Pattern.compile("(?is)isolation\\s+level\\s+repeatable\\s+read");

    static Optional<TransactionCommand> parse(String text) {
        Matcher begin = BEGIN_PATTERN.matcher(text);
        if (begin.matches()) {
            String options = begin.group(1);
            if (options != null && !REPEATABLE_READ.matcher(options.strip()).matches()) {
                throw new SwdbException(SqlStates.FEATURE_NOT_SUPPORTED,
                        "transaction options are not supported yet, only ISOLATION LEVEL REPEATABLE READ: " + options);
            }
            return Optional.of(BEGIN);
        }
        if (COMMIT_PATTERN.matcher(text).matches()) {
            return Optional.of(COMMIT);
        }
        if (ROLLBACK_PATTERN.matcher(text).matches()) {
            return Optional.of(ROLLBACK);
        }
        return Optional.empty();
    }
}
