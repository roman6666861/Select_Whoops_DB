package io.github.roman6666861.swdb.it;

import io.github.roman6666861.swdb.server.ServerConfig;
import io.github.roman6666861.swdb.server.SwdbServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Транзакции через JDBC-драйвер PostgreSQL: {@code setAutoCommit(false)}, {@code commit()},
 * {@code rollback()}. Драйвер решает, слать ли COMMIT, по статусу транзакции в ReadyForQuery.
 */
class TransactionsOverPgWireTest {

    @TempDir
    Path dataDir;

    private SwdbServer server;
    private Connection first;
    private Connection second;

    @BeforeEach
    void start() throws Exception {
        server = SwdbServer.start(new ServerConfig("127.0.0.1", 0, dataDir));
        first = connect();
        second = connect();
        try (Statement s = first.createStatement()) {
            s.executeUpdate("CREATE TABLE t (id INT PRIMARY KEY, v INT)");
            s.executeUpdate("INSERT INTO t VALUES (1, 10)");
        }
    }

    @AfterEach
    void stop() throws Exception {
        second.close();
        first.close();
        server.close();
    }

    @Test
    void commitPublishesChanges() throws Exception {
        first.setAutoCommit(false);
        update(first, "INSERT INTO t VALUES (2, 20)");
        update(first, "UPDATE t SET v = 11 WHERE id = 1");

        assertThat(values(second)).as("not committed yet").containsExactly(10);

        first.commit();

        assertThat(values(second)).containsExactly(11, 20);
    }

    @Test
    void rollbackAndDisconnectDiscardChanges() throws Exception {
        first.setAutoCommit(false);
        update(first, "INSERT INTO t VALUES (2, 20)");
        first.rollback();
        update(first, "INSERT INTO t VALUES (3, 30)");
        first.close(); // отключились, не сделав commit

        assertThat(values(second)).containsExactly(10);
    }

    @Test
    void failedTransactionNeedsRollback() throws Exception {
        first.setAutoCommit(false);
        update(first, "INSERT INTO t VALUES (2, 20)");

        assertSqlState(() -> update(first, "INSERT INTO t VALUES (1, 99)"), "23505");
        assertSqlState(() -> values(first), "25P02");

        first.rollback();
        update(first, "INSERT INTO t VALUES (3, 30)");
        first.commit();
        assertThat(values(second)).containsExactly(10, 30);
    }

    @Test
    void concurrentUpdateOfSameRowFailsForSecondWriter() throws Exception {
        first.setAutoCommit(false);
        second.setAutoCommit(false);
        update(first, "UPDATE t SET v = v + 1 WHERE id = 1");

        CompletableFuture<Void> waiting = CompletableFuture.runAsync(() -> {
            try {
                update(second, "UPDATE t SET v = v + 1 WHERE id = 1");
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(200);
        assertThat(waiting).as("second waits for the row lock").isNotDone();

        first.commit();

        assertThat(waiting).failsWithin(5, TimeUnit.SECONDS).withThrowableThat()
                .havingRootCause().isInstanceOfSatisfying(SQLException.class,
                        e -> assertThat(e.getSQLState()).isEqualTo("40001"));
        second.rollback();
        assertThat(values(second)).containsExactly(11);
    }

    private Connection connect() throws SQLException {
        String url = "jdbc:postgresql://127.0.0.1:" + server.port() + "/swdb?preferQueryMode=simple";
        return DriverManager.getConnection(url, "swdb", "");
    }

    private static void update(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    private static List<Integer> values(Connection c) throws SQLException {
        List<Integer> result = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT v FROM t")) {
            while (rs.next()) {
                result.add(rs.getInt(1));
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
