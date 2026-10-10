package io.github.roman6666861.swdb.it;

import io.github.roman6666861.swdb.server.ServerConfig;
import io.github.roman6666861.swdb.server.SwdbServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Поднимает настоящий сервер и ходит в него штатным JDBC-драйвером PostgreSQL.
 */
class PgWireSmokeTest {

    @TempDir
    static Path dataDir;

    private static SwdbServer server;

    @BeforeAll
    static void startServer() throws InterruptedException {
        server = SwdbServer.start(new ServerConfig("127.0.0.1", 0, dataDir));
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @Test
    void selectOne() throws SQLException {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    void typedColumns() throws SQLException {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT 40 + 2 AS answer, 'hi' AS greeting, TRUE AS flag, NULL AS nothing")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("answer")).isEqualTo(42);
            assertThat(rs.getString("greeting")).isEqualTo("hi");
            assertThat(rs.getBoolean("flag")).isTrue();
            assertThat(rs.getObject("nothing")).isNull();
        }
    }

    @Test
    void syntaxErrorKeepsConnectionUsable() throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeQuery("SELEKT 1"))
                    .isInstanceOfSatisfying(SQLException.class,
                            e -> assertThat(e.getSQLState()).isEqualTo("42601"));

            try (ResultSet rs = s.executeQuery("SELECT 2")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(2);
            }
        }
    }

    // Extended query protocol сервер пока не поддерживает, поэтому preferQueryMode=simple.
    private static Connection connect() throws SQLException {
        String url = "jdbc:postgresql://127.0.0.1:" + server.port() + "/swdb?preferQueryMode=simple";
        return DriverManager.getConnection(url, "swdb", "");
    }
}
