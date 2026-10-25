package io.github.roman6666861.swdb.common;

/**
 * SQLSTATE-коды в терминах PostgreSQL, которые использует сервер.
 */
public final class SqlStates {

    public static final String SYNTAX_ERROR = "42601";
    public static final String SYNTAX_ERROR_OR_ACCESS_RULE_VIOLATION = "42000";
    public static final String UNDEFINED_TABLE = "42P01";
    public static final String UNDEFINED_COLUMN = "42703";
    public static final String UNDEFINED_PARAMETER = "42P02";
    public static final String DUPLICATE_TABLE = "42P07";
    public static final String DUPLICATE_COLUMN = "42701";
    public static final String INVALID_TABLE_DEFINITION = "42P16";
    public static final String UNIQUE_VIOLATION = "23505";
    public static final String NOT_NULL_VIOLATION = "23502";
    public static final String DUPLICATE_PREPARED_STATEMENT = "42P05";
    public static final String DUPLICATE_CURSOR = "42P03";
    public static final String INVALID_SQL_STATEMENT_NAME = "26000";
    public static final String INVALID_CURSOR_NAME = "34000";
    public static final String INVALID_TEXT_REPRESENTATION = "22P02";
    public static final String INVALID_BINARY_REPRESENTATION = "22P03";
    public static final String STRING_DATA_RIGHT_TRUNCATION = "22001";
    public static final String NUMERIC_VALUE_OUT_OF_RANGE = "22003";
    public static final String DIVISION_BY_ZERO = "22012";
    public static final String IN_FAILED_SQL_TRANSACTION = "25P02";
    public static final String SERIALIZATION_FAILURE = "40001";
    public static final String DEADLOCK_DETECTED = "40P01";
    public static final String LOCK_NOT_AVAILABLE = "55P03";
    public static final String FEATURE_NOT_SUPPORTED = "0A000";
    public static final String PROTOCOL_VIOLATION = "08P01";
    public static final String INTERNAL_ERROR = "XX000";

    private SqlStates() {
    }
}
