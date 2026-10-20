package io.github.roman6666861.swdb.sql;

import io.github.roman6666861.swdb.catalog.Catalog;
import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
import io.github.roman6666861.swdb.sql.schema.SwdbSchema;
import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.config.CalciteConnectionProperty;
import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.jdbc.Driver;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.ddl.SqlDdlParserImpl;

import java.sql.SQLException;
import java.util.Properties;

/**
 * SQL-движок на Apache Calcite. Каждая клиентская сессия получает свой {@link SqlSession}.
 */
public final class SqlEngine {

    private static final String URL = "jdbc:calcite:";

    private final Catalog catalog;
    private final Driver driver = new Driver().withPrepareFactory(SwdbPrepare::new);
    private final Properties properties = postgresLikeProperties();
    private final SqlParser.Config parserConfig = SqlParser.config()
            .withParserFactory(SqlDdlParserImpl.FACTORY)
            .withUnquotedCasing(Casing.TO_LOWER)
            .withQuotedCasing(Casing.UNCHANGED)
            .withQuoting(Quoting.DOUBLE_QUOTE)
            .withCaseSensitive(true);

    public SqlEngine(Catalog catalog) {
        this.catalog = catalog;
    }

    public SqlSession openSession() {
        try {
            CalciteConnection connection = driver.connect(URL, properties).unwrap(CalciteConnection.class);
            SchemaPlus schema = connection.getRootSchema().add(SwdbSchema.NAME, new SwdbSchema(catalog));
            // Без кэша Calcite спрашивает список таблиц у схемы при каждом запросе и видит свежий DDL.
            schema.setCacheEnabled(false);
            connection.setSchema(SwdbSchema.NAME);
            return new SqlSession(connection, parserConfig, new DdlExecutor(catalog));
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
        p.setProperty(CalciteConnectionProperty.TYPE_SYSTEM.camelName(), SwdbTypeSystem.class.getName() + "#INSTANCE");
        return p;
    }
}
