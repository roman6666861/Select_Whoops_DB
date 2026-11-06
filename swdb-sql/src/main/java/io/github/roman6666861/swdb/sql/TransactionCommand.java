package io.github.roman6666861.swdb.sql;

import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Команды управления транзакцией в синтаксисе PostgreSQL. Грамматика Calcite их не знает,
 * поэтому они распознаются по тексту до разбора.
 */
sealed interface TransactionCommand {

    /** @param level уровень изоляции из {@code ISOLATION LEVEL ...} или уровень по умолчанию */
    record Begin(IsolationLevel level) implements TransactionCommand {
    }

    record Commit() implements TransactionCommand {
    }

    record Rollback() implements TransactionCommand {
    }

    /** {@code BEGIN [WORK | TRANSACTION] [параметры]} или {@code START TRANSACTION [параметры]}. */
    Pattern BEGIN_PATTERN =
            Pattern.compile("(?is)(?:begin(?:\\s+(?:work|transaction))?|start\\s+transaction)(?:\\s+(.*))?");
    Pattern COMMIT_PATTERN = Pattern.compile("(?is)(?:commit|end)(?:\\s+(?:work|transaction))?");
    Pattern ROLLBACK_PATTERN = Pattern.compile("(?is)(?:rollback|abort)(?:\\s+(?:work|transaction))?");
    Pattern ISOLATION_LEVEL = Pattern.compile(
            "(?is)isolation\\s+level\\s+(read\\s+uncommitted|read\\s+committed|repeatable\\s+read|serializable)");

    static Optional<TransactionCommand> parse(String text) {
        Matcher begin = BEGIN_PATTERN.matcher(text);
        if (begin.matches()) {
            String options = begin.group(1);
            return Optional.of(new Begin(options == null ? IsolationLevel.DEFAULT : isolationLevel(options.strip())));
        }
        if (COMMIT_PATTERN.matcher(text).matches()) {
            return Optional.of(new Commit());
        }
        if (ROLLBACK_PATTERN.matcher(text).matches()) {
            return Optional.of(new Rollback());
        }
        return Optional.empty();
    }

    private static IsolationLevel isolationLevel(String options) {
        Matcher level = ISOLATION_LEVEL.matcher(options);
        if (!level.matches()) {
            throw new SwdbException(SqlStates.FEATURE_NOT_SUPPORTED,
                    "transaction options are not supported yet, only ISOLATION LEVEL: " + options);
        }
        return switch (level.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ")) {
            case "read uncommitted", "read committed" -> IsolationLevel.READ_COMMITTED;
            case "repeatable read" -> IsolationLevel.REPEATABLE_READ;
            default -> IsolationLevel.SERIALIZABLE;
        };
    }
}
