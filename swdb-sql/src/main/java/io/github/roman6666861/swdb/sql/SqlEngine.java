package io.github.roman6666861.swdb.sql;

import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.config.CalciteConnectionProperty;
import org.apache.calcite.jdbc.Driver;
import org.apache.calcite.sql.parser.SqlParser;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * SQL-движок на Apache Calcite. Каждая клиентская сессия получает свой {@link SqlSession}.
 */
public final class SqlEngine {

    private static final String URL = "jdbc:calcite:";

    private final Driver driver = new Driver();
    private final Properties properties = postgresLikeProperties();
    private final SqlParser.Config parserConfig = SqlParser.config()
            .withUnquotedCasing(Casing.TO_LOWER)
            .withQuotedCasing(Casing.UNCHANGED)
            .withQuoting(Quoting.DOUBLE_QUOTE)
            .withCaseSensitive(true);

    public SqlSession openSession() {
        try {
            Connection connection = driver.connect(URL, properties);
            return new SqlSession(connection, parserConfig);
        } catch (SQLException e) {
            throw new SwdbException(SqlStates.INTERNAL_ERROR, "cannot open SQL session: " + e.getMessage(), e);
        }
    }

    /** Лексика как в PostgreSQL: идентификаторы без кавычек приводятся к нижнему регистру. */
    private static Properties postgresLikeProperties() {
        Properties p = new Properties();
        p.setProperty(CalciteConnectionProperty.UNQUOTED_CASING.camelName(), Casing.TO_LOWER.name());
        p.setProperty(CalciteConnectionProperty.QUOTED_CASING.camelName(), Casing.UNCHANGED.name());
        p.setProperty(CalciteConnectionProperty.QUOTING.camelName(), Quoting.DOUBLE_QUOTE.name());
        p.setProperty(CalciteConnectionProperty.CASE_SENSITIVE.camelName(), "true");
        p.setProperty(CalciteConnectionProperty.FUN.camelName(), "standard,postgresql");
        return p;
    }
}
