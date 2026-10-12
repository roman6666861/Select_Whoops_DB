package io.github.roman6666861.swdb.it;

import io.github.roman6666861.swdb.server.ServerConfig;
import io.github.roman6666861.swdb.server.SwdbServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Таблицы через настоящий сервер и JDBC-драйвер PostgreSQL.
 */
class TablesOverPgWireTest {

    @TempDir
    Path dataDir;

    @Test
    void createInsertSelectDelete() throws Exception {
        try (SwdbServer server = start(); Connection c = connect(server); Statement s = c.createStatement()) {
            assertThat(s.executeUpdate("CREATE TABLE users (id INT, name VARCHAR(20), PRIMARY KEY (id))")).isZero();
            assertThat(s.executeUpdate("INSERT INTO users VALUES (2, 'bob'), (1, 'ann'), (3, 'carol')")).isEqualTo(3);

            assertThat(names(s, "SELECT name FROM users")).containsExactly("ann", "bob", "carol");
            assertThat(names(s, "SELECT name FROM users WHERE id >= 2 ORDER BY name DESC")).containsExactly("carol", "bob");

            assertThat(s.executeUpdate("DELETE FROM users WHERE id = 2")).isEqualTo(1);
            assertThat(names(s, "SELECT name FROM users")).containsExactly("ann", "carol");
        }
    }

    @Test
    void constraintErrorsReachClientWithSqlState() throws Exception {
        try (SwdbServer server = start(); Connection c = connect(server); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE t (id INT, PRIMARY KEY (id))");
            s.executeUpdate("INSERT INTO t VALUES (1)");

            assertSqlState(() -> s.executeUpdate("INSERT INTO t VALUES (1)"), "23505");
            assertSqlState(() -> s.executeQuery("SELECT id FROM missing"), "42P01");
            assertSqlState(() -> s.executeUpdate("UPDATE t SET id = 2"), "0A000");

            // После ошибок соединение продолжает работать.
            assertThat(names(s, "SELECT CAST(id AS VARCHAR) FROM t")).containsExactly("1");
        }
    }

    @Test
    void dataSurvivesServerRestart() throws Exception {
        try (SwdbServer server = start(); Connection c = connect(server); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE notes (id INT, text VARCHAR(100), PRIMARY KEY (id))");
            s.executeUpdate("INSERT INTO notes VALUES (1, 'survives restart')");
        }
        try (SwdbServer server = start(); Connection c = connect(server); Statement s = c.createStatement()) {
            assertThat(names(s, "SELECT text FROM notes")).containsExactly("survives restart");
        }
    }

    private SwdbServer start() throws InterruptedException {
        return SwdbServer.start(new ServerConfig("127.0.0.1", 0, dataDir));
    }

    private static Connection connect(SwdbServer server) throws SQLException {
        String url = "jdbc:postgresql://127.0.0.1:" + server.port() + "/swdb?preferQueryMode=simple";
        return DriverManager.getConnection(url, "swdb", "");
    }

    private static List<String> names(Statement s, String sql) throws SQLException {
        List<String> result = new ArrayList<>();
        try (ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                result.add(rs.getString(1));
            }
        }
        return result;
    }

    private static void assertSqlState(ThrowingRunnable action, String sqlState) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(sqlState));
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
