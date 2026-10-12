package io.github.roman6666861.swdb.sql;

import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
import org.apache.calcite.runtime.CalciteContextException;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlCreate;
import org.apache.calcite.sql.SqlDrop;
import org.apache.calcite.sql.SqlMerge;
import org.apache.calcite.sql.SqlUpdate;
import org.apache.calcite.sql.SqlSetOption;
import org.apache.calcite.sql.ddl.SqlCreateTable;
import org.apache.calcite.sql.ddl.SqlDropTable;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Сессия клиента: выполняет SQL-операторы по одному. Не потокобезопасна.
 */
public final class SqlSession implements AutoCloseable {

    private final Connection connection;
    private final SqlParser.Config parserConfig;
    private final DdlExecutor ddl;
    private final Map<String, String> settings = new HashMap<>();

    SqlSession(Connection connection, SqlParser.Config parserConfig, DdlExecutor ddl) {
        this.connection = connection;
        this.parserConfig = parserConfig;
        this.ddl = ddl;
    }

    /** Значение параметра сессии, установленного через {@code SET name = value}. */
    public Optional<String> setting(String name) {
        return Optional.ofNullable(settings.get(name));
    }

    public StatementResult execute(String sql) {
        String text = stripTrailingSemicolons(sql);
        if (text.isEmpty()) {
            return new StatementResult.Empty();
        }
        // SET и DDL Calcite JDBC не выполняет: их обрабатываем сами, остальное отдаём Calcite.
        return switch (parse(text)) {
            case SqlSetOption set -> applySetting(set);
            case SqlCreateTable create -> ddl.createTable(create);
            case SqlDropTable drop -> ddl.dropTable(drop);
            case SqlCreate create -> throw unsupported(create.getOperator().getName());
            case SqlDrop drop -> throw unsupported(drop.getOperator().getName());
            // Исполнитель Calcite (Enumerable) умеет только INSERT и DELETE.
            case SqlUpdate _ -> throw unsupported("UPDATE");
            case SqlMerge _ -> throw unsupported("MERGE");
            default -> executeQuery(text);
        };
    }

    private StatementResult executeQuery(String text) {
        try (Statement statement = connection.createStatement()) {
            if (statement.execute(text)) {
                try (ResultSet rs = statement.getResultSet()) {
                    return readRows(rs);
                }
            }
            return new StatementResult.Update(firstKeyword(text), statement.getLargeUpdateCount());
        } catch (SQLException | RuntimeException e) {
            throw translate(e);
        } catch (AssertionError e) {
            // Calcite проверяет свои инварианты через assert; клиент должен получить ошибку, а не обрыв связи.
            throw new SwdbException(SqlStates.INTERNAL_ERROR, "internal error: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw translate(e);
        }
    }

    private SqlNode parse(String text) {
        try {
            return SqlParser.create(text, parserConfig).parseStmt();
        } catch (SqlParseException e) {
            throw new SwdbException(SqlStates.SYNTAX_ERROR, e.getMessage(), e);
        }
    }

    private StatementResult applySetting(SqlSetOption set) {
        String name = set.name().toString();
        SqlNode value = set.getValue();
        if (value == null) {
            settings.remove(name);
            return new StatementResult.Update("RESET", 0);
        }
        String text = value instanceof SqlLiteral literal ? literal.toValue() : value.toString();
        settings.put(name, text);
        return new StatementResult.Update("SET", 0);
    }

    private static StatementResult.Rows readRows(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int count = meta.getColumnCount();
        List<Column> columns = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            columns.add(new Column(meta.getColumnLabel(i), meta.getColumnType(i)));
        }
        List<List<Object>> rows = new ArrayList<>();
        while (rs.next()) {
            Object[] row = new Object[count];
            for (int i = 0; i < count; i++) {
                row[i] = rs.getObject(i + 1);
            }
            rows.add(Arrays.asList(row));
        }
        return new StatementResult.Rows(columns, rows);
    }

    private static String stripTrailingSemicolons(String sql) {
        String text = sql.strip();
        while (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1).strip();
        }
        return text;
    }

    private static String firstKeyword(String sql) {
        return sql.split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
    }

    private static SwdbException unsupported(String statement) {
        return new SwdbException(SqlStates.FEATURE_NOT_SUPPORTED, statement + " is not supported yet");
    }

    /**
     * Ищет в цепочке причин понятную ошибку и назначает ей SQLSTATE: нашу собственную
     * (например, нарушение первичного ключа при INSERT), ошибку парсера или валидатора Calcite.
     */
    private static SwdbException translate(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SwdbException swdb) {
                return swdb;
            }
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SqlParseException) {
                return new SwdbException(SqlStates.SYNTAX_ERROR, t.getMessage(), e);
            }
            if (t instanceof CalciteContextException) {
                return new SwdbException(validationSqlState(t.getCause()), t.getMessage(), e);
            }
        }
        return new SwdbException(SqlStates.INTERNAL_ERROR, e.getMessage(), e);
    }

    /** У валидатора Calcite нет кодов ошибок, поэтому самые частые различаем по тексту. */
    private static String validationSqlState(Throwable cause) {
        String message = cause == null ? "" : String.valueOf(cause.getMessage());
        if (message.matches("Object '.*' not found.*")) {
            return SqlStates.UNDEFINED_TABLE;
        }
        if (message.matches("Column '.*' not found.*")) {
            return SqlStates.UNDEFINED_COLUMN;
        }
        return SqlStates.SYNTAX_ERROR_OR_ACCESS_RULE_VIOLATION;
    }
}
