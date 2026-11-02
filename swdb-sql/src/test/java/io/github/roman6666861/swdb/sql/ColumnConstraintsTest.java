package io.github.roman6666861.swdb.sql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ColumnConstraintsTest {

    @Test
    void columnKeyBecomesTableConstraint() {
        assertThat(ColumnConstraints.toTableConstraints("CREATE TABLE t (id INT PRIMARY KEY, v INT)"))
                .isEqualTo("CREATE TABLE t (id INT , v INT, PRIMARY KEY (id))");
    }

    @Test
    void otherColumnOptionsStay() {
        assertThat(ColumnConstraints.toTableConstraints("create table t (id int not null primary key, v int)"))
                .isEqualTo("create table t (id int not null , v int, PRIMARY KEY (id))");
        assertThat(ColumnConstraints.toTableConstraints("CREATE TABLE t (v INT, id INT PRIMARY KEY NOT NULL)"))
                .isEqualTo("CREATE TABLE t (v INT, id INT  NOT NULL, PRIMARY KEY (id))");
    }

    @Test
    void commasInsideTypesAreNotColumnSeparators() {
        assertThat(ColumnConstraints.toTableConstraints(
                "CREATE TABLE IF NOT EXISTS public.t (price DECIMAL(10, 2), code VARCHAR(5) PRIMARY KEY)"))
                .isEqualTo("CREATE TABLE IF NOT EXISTS public.t (price DECIMAL(10, 2), code VARCHAR(5) , PRIMARY KEY (code))");
    }

    @Test
    void quotedNamesAndCommentsAreNotParsed() {
        assertThat(ColumnConstraints.toTableConstraints("CREATE TABLE t (\"My \"\"Id\"\"\" INT PRIMARY KEY)"))
                .isEqualTo("CREATE TABLE t (\"My \"\"Id\"\"\" INT , PRIMARY KEY (\"My \"\"Id\"\"\"))");
        // Колонка с именем "primary key" и комментарий с теми же словами — не ограничение.
        String unchanged = "CREATE TABLE t (\"primary key\" INT, v INT /* primary key, (later) */, PRIMARY KEY (v))";
        assertThat(ColumnConstraints.toTableConstraints(unchanged)).isSameAs(unchanged);
    }

    @Test
    void textWithoutColumnKeyIsReturnedAsIs() {
        for (String sql : new String[]{
                "CREATE TABLE t (id INT, PRIMARY KEY (id))",
                "SELECT 'id INT PRIMARY KEY'",
                "CREATE TABLE t AS SELECT 1 AS primary_key",
                "CREATE TABLE t (id INT PRIMARY KEY"}) {
            assertThat(ColumnConstraints.toTableConstraints(sql)).isSameAs(sql);
        }
    }

    @Test
    void columnUniqueBecomesTableConstraint() {
        assertThat(ColumnConstraints.toTableConstraints("CREATE TABLE t (id INT PRIMARY KEY, email VARCHAR(50) NOT NULL UNIQUE)"))
                .isEqualTo("CREATE TABLE t (id INT , email VARCHAR(50) NOT NULL , PRIMARY KEY (id), UNIQUE (email))");
        // Оба ограничения в одной колонке.
        assertThat(ColumnConstraints.toTableConstraints("create table t (id int unique primary key)"))
                .isEqualTo("create table t (id int  , UNIQUE (id), PRIMARY KEY (id))");
    }

    @Test
    void tableUniqueAndQuotedUniqueStay() {
        for (String sql : new String[]{
                "CREATE TABLE t (id INT, e INT, PRIMARY KEY (id), UNIQUE (e))",
                "CREATE TABLE t (id INT, e INT, PRIMARY KEY (id), CONSTRAINT t_e UNIQUE (e))",
                "CREATE TABLE t (\"unique\" INT, v INT /* unique */, PRIMARY KEY (v))"}) {
            assertThat(ColumnConstraints.toTableConstraints(sql)).isSameAs(sql);
        }
    }

    @Test
    void severalColumnKeysGiveSeveralConstraints() {
        assertThat(ColumnConstraints.toTableConstraints("CREATE TABLE t (a INT PRIMARY KEY, b INT PRIMARY KEY)"))
                .isEqualTo("CREATE TABLE t (a INT , b INT , PRIMARY KEY (a), PRIMARY KEY (b))");
    }
}
