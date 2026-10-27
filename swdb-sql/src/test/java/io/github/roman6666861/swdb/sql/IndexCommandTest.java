package io.github.roman6666861.swdb.sql;

import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexCommandTest {

    @Test
    void createIndex() {
        assertThat(IndexCommand.parse("CREATE INDEX users_email ON users (email)")).contains(
                new IndexCommand.Create("users_email", "users", List.of("email"), false, false));
        assertThat(IndexCommand.parse("create unique index if not exists i on public.t using btree (a asc, b)"))
                .contains(new IndexCommand.Create("i", "t", List.of("a", "b"), true, true));
    }

    @Test
    void nameIsOptional() {
        assertThat(IndexCommand.parse("CREATE INDEX ON t(a)"))
                .contains(new IndexCommand.Create(null, "t", List.of("a"), false, false));
    }

    @Test
    void quotedNamesKeepCaseAndAreNotKeywords() {
        assertThat(IndexCommand.parse("CREATE INDEX \"On\" ON \"My \"\"T\"\"\" (\"on\", Upper)")).contains(
                new IndexCommand.Create("On", "My \"T\"", List.of("on", "upper"), false, false));
    }

    @Test
    void commentsAreSkipped() {
        assertThat(IndexCommand.parse("CREATE /* no unique */ INDEX i -- name\n ON t (a)"))
                .contains(new IndexCommand.Create("i", "t", List.of("a"), false, false));
    }

    @Test
    void dropIndex() {
        assertThat(IndexCommand.parse("DROP INDEX i")).contains(new IndexCommand.Drop("i", false));
        assertThat(IndexCommand.parse("drop index if exists public.i restrict")).contains(new IndexCommand.Drop("i", true));
    }

    @Test
    void otherStatementsAreNotIndexCommands() {
        for (String sql : new String[]{
                "CREATE TABLE t (id INT PRIMARY KEY)",
                "DROP TABLE t",
                "SELECT 'CREATE INDEX i ON t (a)'",
                "CREATE UNIQUE something",
                "\"create\" INDEX i ON t (a)"}) {
            assertThat(IndexCommand.parse(sql)).as(sql).isEqualTo(Optional.empty());
        }
    }

    @Test
    void syntaxErrors() {
        assertSqlState("CREATE INDEX i ON t", SqlStates.SYNTAX_ERROR, "syntax error at end of input");
        assertSqlState("CREATE INDEX i ON t ()", SqlStates.SYNTAX_ERROR, "syntax error at or near \")\"");
        assertSqlState("CREATE INDEX i t (a)", SqlStates.SYNTAX_ERROR, "syntax error at or near \"t\"");
        assertSqlState("CREATE INDEX i ON t (a) WHERE a > 0", SqlStates.SYNTAX_ERROR, "syntax error at or near \"where\"");
        assertSqlState("DROP INDEX i, j", SqlStates.FEATURE_NOT_SUPPORTED, "DROP INDEX with several indexes is not supported yet");
        assertSqlState("CREATE INDEX i ON t (a DESC)", SqlStates.FEATURE_NOT_SUPPORTED, "DESC in indexes is not supported yet");
        assertSqlState("CREATE INDEX i ON t USING hash (a)", SqlStates.FEATURE_NOT_SUPPORTED,
                "index method \"hash\" is not supported yet");
        assertSqlState("CREATE INDEX i ON other.t (a)", SqlStates.FEATURE_NOT_SUPPORTED,
                "schemas other than \"public\" is not supported yet");
    }

    private static void assertSqlState(String sql, String sqlState, String message) {
        assertThatThrownBy(() -> IndexCommand.parse(sql))
                .as(sql)
                .isInstanceOfSatisfying(SwdbException.class, e -> {
                    assertThat(e.sqlState()).isEqualTo(sqlState);
                    assertThat(e.getMessage()).isEqualTo(message);
                });
    }
}
