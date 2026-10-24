package io.github.roman6666861.swdb.it;

import io.github.roman6666861.swdb.server.ServerConfig;
import io.github.roman6666861.swdb.server.SwdbServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JDBC-драйвер PostgreSQL в обычном режиме: все запросы идут через extended query protocol
 * (Parse/Bind/Describe/Execute/Sync), значения параметров — отдельно от текста SQL.
 */
class ExtendedProtocolOverPgWireTest {

    @TempDir
    Path dataDir;

    private SwdbServer server;
    private Connection c;

    @BeforeEach
    void start() throws Exception {
        server = SwdbServer.start(new ServerConfig("127.0.0.1", 0, dataDir));
        // Двоичный формат значений пока не поддерживается.
        c = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:" + server.port() + "/swdb?binaryTransfer=false", "swdb", "");
        try (Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(20), price DECIMAL(10, 2), "
                    + "big BIGINT, ok BOOLEAN, d DATE, ts TIMESTAMP)");
        }
    }

    @AfterEach
    void stop() throws Exception {
        c.close();
        server.close();
    }

    @Test
    void plainStatementGoesThroughExtendedProtocolToo() throws Exception {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT 40 + 2 AS answer")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("answer")).isEqualTo(42);
        }
    }

    @Test
    void preparedInsertAndSelectReusedManyTimes() throws Exception {
        // Больше prepareThreshold (5): драйвер переходит на именованный оператор на сервере.
        try (PreparedStatement insert = c.prepareStatement("INSERT INTO t (id, name) VALUES (?, ?)")) {
            for (int i = 1; i <= 10; i++) {
                insert.setInt(1, i);
                insert.setString(2, "n" + i);
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }
        }
        try (PreparedStatement select = c.prepareStatement("SELECT name FROM t WHERE id = ?")) {
            for (int i = 1; i <= 10; i++) {
                select.setInt(1, i);
                assertThat(strings(select)).containsExactly("n" + i);
            }
            select.setInt(1, 99);
            assertThat(strings(select)).isEmpty();
        }
    }

    @Test
    void parameterAndColumnTypes() throws Exception {
        try (PreparedStatement insert = c.prepareStatement("INSERT INTO t VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            insert.setInt(1, 1);
            insert.setString(2, "ann");
            insert.setBigDecimal(3, new BigDecimal("12.50"));
            insert.setLong(4, 9_000_000_000L);
            insert.setBoolean(5, true);
            insert.setDate(6, Date.valueOf("2026-10-21"));
            insert.setTimestamp(7, Timestamp.valueOf("2026-10-21 12:30:45.123"));
            insert.executeUpdate();

            insert.setInt(1, 2);
            for (int i = 2; i <= 7; i++) {
                insert.setNull(i, Types.NULL);
            }
            insert.executeUpdate();
        }

        try (PreparedStatement select = c.prepareStatement("SELECT * FROM t WHERE id = ?")) {
            select.setInt(1, 1);
            ResultSetMetaData meta = select.getMetaData();
            assertThat(meta.getColumnType(3)).isEqualTo(Types.NUMERIC);
            try (ResultSet rs = select.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("name")).isEqualTo("ann");
                assertThat(rs.getBigDecimal("price")).isEqualByComparingTo("12.50");
                assertThat(rs.getLong("big")).isEqualTo(9_000_000_000L);
                assertThat(rs.getBoolean("ok")).isTrue();
                assertThat(rs.getDate("d")).isEqualTo(Date.valueOf("2026-10-21"));
                assertThat(rs.getTimestamp("ts")).isEqualTo(Timestamp.valueOf("2026-10-21 12:30:45.123"));
            }
            select.setInt(1, 2);
            try (ResultSet rs = select.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject("name")).isNull();
                assertThat(rs.getObject("price")).isNull();
            }
        }
    }

    @Test
    void batchOfInsertsInOneRoundTrip() throws Exception {
        try (PreparedStatement insert = c.prepareStatement("INSERT INTO t (id, name) VALUES (?, ?)")) {
            for (int i = 1; i <= 3; i++) {
                insert.setInt(1, i);
                insert.setString(2, "n" + i);
                insert.addBatch();
            }
            assertThat(insert.executeBatch()).containsExactly(1, 1, 1);
        }
        assertThat(count()).isEqualTo(3);
    }

    @Test
    void fetchSizeReadsRowsInPortions() throws Exception {
        try (Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO t (id) VALUES (1), (2), (3), (4), (5)");
        }
        // Порциями драйвер читает только внутри транзакции: Execute с maxRows и PortalSuspended.
        c.setAutoCommit(false);
        try (PreparedStatement select = c.prepareStatement("SELECT id FROM t")) {
            select.setFetchSize(2);
            List<Integer> ids = new ArrayList<>();
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getInt(1));
                }
            }
            assertThat(ids).containsExactly(1, 2, 3, 4, 5);
        }
        c.commit();
    }

    @Test
    void errorsKeepConnectionUsable() throws Exception {
        try (PreparedStatement insert = c.prepareStatement("INSERT INTO t (id) VALUES (?)")) {
            insert.setInt(1, 1);
            insert.executeUpdate();

            assertSqlState(insert::executeUpdate, "23505");
            assertSqlState(() -> c.prepareStatement("SELECT nope FROM t").executeQuery(), "42703");

            insert.setInt(1, 2);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void parametersAreNotSql() throws Exception {
        try (PreparedStatement select = c.prepareStatement("SELECT id FROM t WHERE name = ?")) {
            select.setString(1, "x' OR '1'='1");
            assertThat(strings(select)).isEmpty();
        }
    }

    private int count() throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM t")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static List<String> strings(PreparedStatement select) throws SQLException {
        List<String> result = new ArrayList<>();
        try (ResultSet rs = select.executeQuery()) {
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
