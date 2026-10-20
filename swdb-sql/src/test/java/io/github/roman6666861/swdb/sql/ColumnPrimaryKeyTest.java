package io.github.roman6666861.swdb.sql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ColumnPrimaryKeyTest {

    @Test
    void columnKeyBecomesTableConstraint() {
        assertThat(ColumnPrimaryKey.toTableConstraint("CREATE TABLE t (id INT PRIMARY KEY, v INT)"))
                .isEqualTo("CREATE TABLE t (id INT , v INT, PRIMARY KEY (id))");
    }

    @Test
    void otherColumnOptionsStay() {
        assertThat(ColumnPrimaryKey.toTableConstraint("create table t (id int not null primary key, v int)"))
                .isEqualTo("create table t (id int not null , v int, PRIMARY KEY (id))");
        assertThat(ColumnPrimaryKey.toTableConstraint("CREATE TABLE t (v INT, id INT PRIMARY KEY NOT NULL)"))
                .isEqualTo("CREATE TABLE t (v INT, id INT  NOT NULL, PRIMARY KEY (id))");
    }

    @Test
    void commasInsideTypesAreNotColumnSeparators() {
        assertThat(ColumnPrimaryKey.toTableConstraint(
                "CREATE TABLE IF NOT EXISTS public.t (price DECIMAL(10, 2), code VARCHAR(5) PRIMARY KEY)"))
                .isEqualTo("CREATE TABLE IF NOT EXISTS public.t (price DECIMAL(10, 2), code VARCHAR(5) , PRIMARY KEY (code))");
    }

    @Test
    void quotedNamesAndCommentsAreNotParsed() {
        assertThat(ColumnPrimaryKey.toTableConstraint("CREATE TABLE t (\"My \"\"Id\"\"\" INT PRIMARY KEY)"))
                .isEqualTo("CREATE TABLE t (\"My \"\"Id\"\"\" INT , PRIMARY KEY (\"My \"\"Id\"\"\"))");
        // Колонка с именем "primary key" и комментарий с теми же словами — не ограничение.
        String unchanged = "CREATE TABLE t (\"primary key\" INT, v INT /* primary key, (later) */, PRIMARY KEY (v))";
        assertThat(ColumnPrimaryKey.toTableConstraint(unchanged)).isSameAs(unchanged);
    }

    @Test
    void textWithoutColumnKeyIsReturnedAsIs() {
        for (String sql : new String[]{
                "CREATE TABLE t (id INT, PRIMARY KEY (id))",
                "SELECT 'id INT PRIMARY KEY'",
                "CREATE TABLE t AS SELECT 1 AS primary_key",
                "CREATE TABLE t (id INT PRIMARY KEY"}) {
            assertThat(ColumnPrimaryKey.toTableConstraint(sql)).isSameAs(sql);
        }
    }

    @Test
    void severalColumnKeysGiveSeveralConstraints() {
        assertThat(ColumnPrimaryKey.toTableConstraint("CREATE TABLE t (a INT PRIMARY KEY, b INT PRIMARY KEY)"))
                .isEqualTo("CREATE TABLE t (a INT , b INT , PRIMARY KEY (a), PRIMARY KEY (b))");
    }
}
