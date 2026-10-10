package io.github.roman6666861.swdb.common;

/**
 * SQLSTATE-коды в терминах PostgreSQL, которые использует сервер.
 */
public final class SqlStates {

    public static final String SYNTAX_ERROR = "42601";
    public static final String SYNTAX_ERROR_OR_ACCESS_RULE_VIOLATION = "42000";
    public static final String FEATURE_NOT_SUPPORTED = "0A000";
    public static final String PROTOCOL_VIOLATION = "08P01";
    public static final String INTERNAL_ERROR = "XX000";

    private SqlStates() {
    }
}
