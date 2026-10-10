package io.github.roman6666861.swdb.sql;

import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlSessionTest {

    private final SqlSession session = new SqlEngine().openSession();

    @AfterEach
    void closeSession() {
        session.close();
    }

    @Test
    void selectsConstantExpression() {
        StatementResult result = session.execute("SELECT 1 + 2 AS three;");

        assertThat(result).isInstanceOfSatisfying(StatementResult.Rows.class, rows -> {
            assertThat(rows.columns()).extracting(Column::name).containsExactly("three");
            assertThat(rows.rows()).containsExactly(List.of(3));
        });
    }

    @Test
    void emptyQuery() {
        assertThat(session.execute(" ; ")).isInstanceOf(StatementResult.Empty.class);
    }

    @Test
    void setStoresSessionSetting() {
        StatementResult result = session.execute("SET application_name = 'psql'");

        assertThat(result).isEqualTo(new StatementResult.Update("SET", 0));
        assertThat(session.setting("application_name")).contains("psql");
    }

    @Test
    void syntaxErrorHasSqlState() {
        assertThatThrownBy(() -> session.execute("SELEKT 1"))
                .isInstanceOfSatisfying(SwdbException.class,
                        e -> assertThat(e.sqlState()).isEqualTo(SqlStates.SYNTAX_ERROR));
    }
}
