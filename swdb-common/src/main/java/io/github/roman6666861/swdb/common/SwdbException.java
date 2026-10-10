package io.github.roman6666861.swdb.common;

/**
 * Базовое исключение СУБД. Несёт SQLSTATE-код, который сервер отдаёт клиенту в ErrorResponse.
 */
public class SwdbException extends RuntimeException {

    private final String sqlState;

    public SwdbException(String sqlState, String message) {
        super(message);
        this.sqlState = sqlState;
    }

    public SwdbException(String sqlState, String message, Throwable cause) {
        super(message, cause);
        this.sqlState = sqlState;
    }

    public String sqlState() {
        return sqlState;
    }
}
