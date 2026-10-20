package io.github.roman6666861.swdb.common;

/**
 * SQLSTATE-коды в терминах PostgreSQL, которые использует сервер.
 */
public final class SqlStates {

    public static final String SYNTAX_ERROR = "42601";
    public static final String SYNTAX_ERROR_OR_ACCESS_RULE_VIOLATION = "42000";
    public static final String UNDEFINED_TABLE = "42P01";
    public static final String UNDEFINED_COLUMN = "42703";
    public static final String DUPLICATE_TABLE = "42P07";
    public static final String DUPLICATE_COLUMN = "42701";
    public static final String INVALID_TABLE_DEFINITION = "42P16";
    public static final String UNIQUE_VIOLATION = "23505";
    public static final String NOT_NULL_VIOLATION = "23502";
    public static final String STRING_DATA_RIGHT_TRUNCATION = "22001";
    public static final String NUMERIC_VALUE_OUT_OF_RANGE = "22003";
    public static final String DIVISION_BY_ZERO = "22012";
    public static final String SERIALIZATION_FAILURE = "40001";
    public static final String DEADLOCK_DETECTED = "40P01";
    public static final String LOCK_NOT_AVAILABLE = "55P03";
    public static final String FEATURE_NOT_SUPPORTED = "0A000";
    public static final String PROTOCOL_VIOLATION = "08P01";
    public static final String INTERNAL_ERROR = "XX000";

    private SqlStates() {
    }
}
