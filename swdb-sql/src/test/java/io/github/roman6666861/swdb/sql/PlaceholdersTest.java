package io.github.roman6666861.swdb.sql;

import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaceholdersTest {

    @Test
    void numberedParametersBecomePositional() {
        Placeholders p = Placeholders.of("SELECT * FROM t WHERE id = $1 AND v > $2");

        assertThat(p.sql()).isEqualTo("SELECT * FROM t WHERE id = ? AND v > ?");
        assertThat(p.order()).containsExactly(0, 1);
        assertThat(p.count()).isEqualTo(2);
    }

    @Test
    void anyOrderAndRepeats() {
        Placeholders p = Placeholders.of("SELECT * FROM t WHERE v = $2 OR id = $1 OR parent = $1");

        assertThat(p.sql()).isEqualTo("SELECT * FROM t WHERE v = ? OR id = ? OR parent = ?");
        assertThat(p.order()).containsExactly(1, 0, 0);
        assertThat(p.count()).isEqualTo(2);
    }

    @Test
    void stringsCommentsAndNamesAreNotParameters() {
        String sql = "SELECT '$1', \"$2\", a$3 FROM t -- $4\nWHERE id = $1 /* $5 */";
        Placeholders p = Placeholders.of(sql);

        assertThat(p.sql()).isEqualTo("SELECT '$1', \"$2\", a$3 FROM t -- $4\nWHERE id = ? /* $5 */");
        assertThat(p.order()).containsExactly(0);
    }

    @Test
    void noParameters() {
        Placeholders p = Placeholders.of("SELECT 1");

        assertThat(p.sql()).isEqualTo("SELECT 1");
        assertThat(p.order()).isEqualTo(List.of());
        assertThat(p.count()).isZero();
    }

    @Test
    void invalidParameters() {
        assertThatThrownBy(() -> Placeholders.of("SELECT $0"))
                .isInstanceOfSatisfying(SwdbException.class,
                        e -> assertThat(e.sqlState()).isEqualTo(SqlStates.UNDEFINED_PARAMETER));
        // Знак ? в PostgreSQL — не параметр; Calcite принял бы его за параметр без номера.
        assertThatThrownBy(() -> Placeholders.of("SELECT * FROM t WHERE id = ?"))
                .isInstanceOfSatisfying(SwdbException.class,
                        e -> assertThat(e.sqlState()).isEqualTo(SqlStates.SYNTAX_ERROR));
        assertThat(Placeholders.of("SELECT '?'").count()).isZero();
    }
}
