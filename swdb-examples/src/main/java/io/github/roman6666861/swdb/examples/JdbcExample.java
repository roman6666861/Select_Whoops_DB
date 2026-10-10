package io.github.roman6666861.swdb.examples;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Пример подключения к запущенному серверу Select_Whoops_DB через стандартный JDBC.
 * <p>
 * Без аргументов выполняет несколько демонстрационных запросов,
 * с аргументом — выполняет переданный SQL: {@code JdbcExample "SELECT 1"}.
 */
public final class JdbcExample {

    // preferQueryMode=simple: сервер пока не поддерживает prepared statements (extended protocol).
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/swdb?preferQueryMode=simple";
    private static final String USER = "roman";
    private static final String PASSWORD = "";

    public static void main(String[] args) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
            System.out.println("Connected to " + connection.getMetaData().getDatabaseProductName()
                    + " " + connection.getMetaData().getDatabaseProductVersion());

            if (args.length > 0) {
                execute(connection, String.join(" ", args));
                return;
            }
            execute(connection, "SELECT 40 + 2 AS answer, UPPER('select whoops') AS name, CURRENT_DATE AS today");
            execute(connection, "VALUES (1, 'alpha'), (2, 'beta'), (3, 'gamma')");
            execute(connection, "SELEKT 1");
        }
    }

    private static void execute(Connection connection, String sql) {
        System.out.println();
        System.out.println("> " + sql);
        try (Statement statement = connection.createStatement()) {
            // execute() возвращает true, если результат — набор строк, и false для DML/SET.
            if (statement.execute(sql)) {
                try (ResultSet rs = statement.getResultSet()) {
                    printResultSet(rs);
                }
            } else {
                System.out.println("OK (" + statement.getUpdateCount() + ")");
            }
        } catch (SQLException e) {
            // Ошибка от сервера не рвёт соединение: можно выполнять следующие запросы.
            System.out.println("ERROR " + e.getSQLState() + ": " + e.getMessage().lines().findFirst().orElse(""));
        }
    }

    private static void printResultSet(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columns = meta.getColumnCount();
        StringBuilder header = new StringBuilder();
        for (int i = 1; i <= columns; i++) {
            header.append(i > 1 ? " | " : "").append(meta.getColumnLabel(i))
                    .append(" (").append(meta.getColumnTypeName(i)).append(')');
        }
        System.out.println(header);
        int rows = 0;
        while (rs.next()) {
            StringBuilder row = new StringBuilder();
            for (int i = 1; i <= columns; i++) {
                row.append(i > 1 ? " | " : "").append(rs.getString(i));
            }
            System.out.println(row);
            rows++;
        }
        System.out.println("(" + rows + " rows)");
    }
}
