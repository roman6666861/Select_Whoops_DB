package io.github.roman6666861.swdb.sql;

import io.github.roman6666861.swdb.catalog.Catalog;
import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
import io.github.roman6666861.swdb.sql.schema.SessionTransaction;
import org.apache.calcite.runtime.CalciteContextException;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlCreate;
import org.apache.calcite.sql.SqlDrop;
import org.apache.calcite.sql.SqlExplain;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlMerge;
import org.apache.calcite.sql.SqlSetOption;
import org.apache.calcite.sql.ddl.SqlCreateTable;
import org.apache.calcite.sql.ddl.SqlDropTable;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;

import java.sql.Connection;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Сессия клиента: выполняет SQL-операторы по одному. Не потокобезопасна.
 */
public final class SqlSession implements AutoCloseable {

    /** {@code EXPLAIN <запрос>} в стиле PostgreSQL, но не {@code EXPLAIN PLAN ...} в стиле Calcite. */
    private static final Pattern POSTGRES_EXPLAIN = Pattern.compile("(?is)explain\\s+(?!plan\\s)(.+)");
    private static final Column QUERY_PLAN = new Column("QUERY PLAN", Types.VARCHAR);

    private final Connection connection;
    private final SqlParser.Config parserConfig;
    private final DdlExecutor ddl;
    private final TransactionBlock transactions;
    private final Map<String, String> settings = new HashMap<>();

    SqlSession(Connection connection, SqlParser.Config parserConfig, DdlExecutor ddl,
               Catalog catalog, SessionTransaction transaction) {
        this.connection = connection;
        this.parserConfig = parserConfig;
        this.ddl = ddl;
        this.transactions = new TransactionBlock(catalog, transaction);
    }

    /** Значение параметра сессии, установленного через {@code SET name = value}. */
    public Optional<String> setting(String name) {
        return Optional.ofNullable(settings.get(name));
    }

    public TransactionStatus transactionStatus() {
        return transactions.status();
    }

    public StatementResult execute(String sql) {
        return executeNormalized(normalize(sql));
    }

    /**
     * Готовит оператор к многократному выполнению с параметрами {@code $1, $2, ...}
     * (extended query protocol PostgreSQL). SELECT, INSERT, UPDATE и DELETE разбираются
     * и планируются здесь один раз; остальное (BEGIN, SET, DDL, EXPLAIN) параметров не принимает
     * и при выполнении идёт обычным путём, как {@link #execute}.
     */
    public PreparedQuery prepare(String sql) {
        String text = normalize(sql);
        if (text.isEmpty() || TransactionCommand.parse(text).isPresent()) {
            // Команды транзакции готовятся и в сломанной транзакции: иначе не выполнить ROLLBACK.
            return new DeferredQuery(text, List.of());
        }
        return transactions.statement(() -> prepareStatement(text));
    }

    private PreparedQuery prepareStatement(String text) {
        if (IndexCommand.parse(text).isPresent()) {
            return new DeferredQuery(text, List.of());
        }
        Placeholders placeholders = Placeholders.of(text);
        SqlNode node = parse(placeholders.sql());
        if (node instanceof SqlSetOption || node instanceof SqlExplain || node instanceof SqlCreate
                || node instanceof SqlDrop || node instanceof SqlMerge) {
            if (placeholders.count() > 0) {
                throw unsupported("parameters in " + firstKeyword(text));
            }
            return new DeferredQuery(text, node instanceof SqlExplain ? List.of(QUERY_PLAN) : List.of());
        }
        boolean returnsRows = node.getKind().belongsTo(SqlKind.QUERY);
        return jdbc(() -> new CalciteQuery(connection.prepareStatement(placeholders.sql()), text, placeholders, returnsRows));
    }

    private StatementResult executeNormalized(String text) {
        if (text.isEmpty()) {
            return new StatementResult.Empty();
        }
        Optional<TransactionCommand> command = TransactionCommand.parse(text);
        if (command.isPresent()) {
            return transactions.execute(command.get());
        }
        return transactions.statement(() -> executeStatement(text));
    }

    private StatementResult executeStatement(String text) {
        Optional<IndexCommand> index = IndexCommand.parse(text);
        if (index.isPresent()) {
            return switch (index.get()) {
                case IndexCommand.Create create -> ddl.createIndex(outsideTransaction(create, "CREATE INDEX"));
                case IndexCommand.Drop drop -> ddl.dropIndex(outsideTransaction(drop, "DROP INDEX"));
            };
        }
        // SET и DDL Calcite JDBC не выполняет: их обрабатываем сами, остальное отдаём Calcite.
        return switch (parse(text)) {
            case SqlSetOption set -> applySetting(set);
            case SqlCreateTable create -> ddl.createTable(outsideTransaction(create));
            case SqlDropTable drop -> ddl.dropTable(outsideTransaction(drop));
            case SqlExplain _ -> explain(text);
            case SqlCreate create -> throw unsupported(create.getOperator().getName());
            case SqlDrop drop -> throw unsupported(drop.getOperator().getName());
            // Исполнитель Calcite умеет только INSERT и DELETE; UPDATE мы добавили сами (TableModifyRule).
            case SqlMerge _ -> throw unsupported("MERGE");
            default -> executeQuery(text);
        };
    }

    /**
     * DDL меняет каталог сразу, мимо транзакций, поэтому внутри блока BEGIN он запрещён:
     * иначе ROLLBACK не отменил бы CREATE TABLE.
     */
    private <T extends SqlNode> T outsideTransaction(T ddlStatement) {
        return outsideTransaction(ddlStatement, ddlStatement.getKind().sql.replace('_', ' '));
    }

    private <T> T outsideTransaction(T ddlStatement, String name) {
        if (transactions.isOpen()) {
            throw unsupported(name + " inside a transaction block");
        }
        return ddlStatement;
    }

    /**
     * Запрос или DML через Calcite: в транзакции блока BEGIN или в своей (как autocommit
     * в PostgreSQL), где при успехе все изменения оператора фиксируются вместе, а при ошибке
     * не остаётся ни одного.
     */
    private StatementResult executeQuery(String text) {
        return transactions.withTransaction(() -> executeInTransaction(text));
    }

    private StatementResult executeInTransaction(String text) {
        return jdbc(() -> {
            try (Statement statement = connection.createStatement()) {
                return result(statement, statement.execute(text), text);
            }
        });
    }

    /** Результат выполненного оператора: строки или число изменённых строк. */
    private static StatementResult result(Statement statement, boolean hasRows, String text) throws SQLException {
        if (hasRows) {
            try (ResultSet rs = statement.getResultSet()) {
                return readRows(rs);
            }
        }
        return new StatementResult.Update(firstKeyword(text), statement.getLargeUpdateCount());
    }

    @FunctionalInterface
    private interface JdbcCall<T> {
        T call() throws SQLException;
    }

    /** Вызов JDBC-драйвера Calcite: его ошибки переводятся в {@link SwdbException} с SQLSTATE. */
    private static <T> T jdbc(JdbcCall<T> call) {
        try {
            return call.call();
        } catch (SQLException | RuntimeException e) {
            throw translate(e);
        } catch (ExceptionInInitializerError e) {
            // Константы вроде 1 / 0 вычисляются в статическом инициализаторе сгенерированного класса,
            // и исключение приходит обёрнутым в Error.
            throw translate(e);
        } catch (AssertionError e) {
            // Calcite проверяет свои инварианты через assert; клиент должен получить ошибку, а не обрыв связи.
            throw new SwdbException(SqlStates.INTERNAL_ERROR, "internal error: " + e.getMessage(), e);
        }
    }

    /**
     * План запроса. Calcite возвращает его одной многострочной ячейкой в колонке PLAN,
     * а PostgreSQL — колонкой "QUERY PLAN" по строке результата на каждую строку плана.
     * Сам запрос при этом не выполняется.
     */
    private StatementResult explain(String text) {
        StatementResult.Rows result = (StatementResult.Rows) executeQuery(text);
        String plan = (String) result.rows().getFirst().getFirst();
        List<List<Object>> lines = plan.lines().map(line -> List.<Object>of(line)).toList();
        return new StatementResult.Rows(List.of(QUERY_PLAN), lines);
    }

    /** Оператор, который не готовится заранее, а при выполнении идёт обычным путём {@link #execute}. */
    private final class DeferredQuery implements PreparedQuery {

        private final String text;
        private final List<Column> columns;

        DeferredQuery(String text, List<Column> columns) {
            this.text = text;
            this.columns = columns;
        }

        @Override
        public List<Integer> parameterTypes() {
            return List.of();
        }

        @Override
        public List<Column> columns() {
            return columns;
        }

        @Override
        public StatementResult execute(List<Object> parameters) {
            checkParameterCount(parameters, 0);
            return executeNormalized(text);
        }

        @Override
        public void close() {
        }
    }

    /**
     * SELECT или DML, подготовленный Calcite: разбор, проверка и план — один раз, при создании.
     * Типы параметров Calcite выводит из того, где они стоят: в {@code WHERE id = ?} это тип
     * колонки {@code id}, в {@code INSERT INTO t VALUES (?, ?)} — типы колонок таблицы.
     */
    private final class CalciteQuery implements PreparedQuery {

        private final PreparedStatement statement;
        private final String text;
        private final Placeholders placeholders;
        private final List<Integer> parameterTypes;
        private final List<Column> columns;

        CalciteQuery(PreparedStatement statement, String text, Placeholders placeholders, boolean returnsRows)
                throws SQLException {
            this.statement = statement;
            this.text = text;
            this.placeholders = placeholders;
            ParameterMetaData meta = statement.getParameterMetaData();
            Integer[] types = new Integer[placeholders.count()];
            for (int position = 0; position < placeholders.order().size(); position++) {
                int parameter = placeholders.order().get(position);
                if (types[parameter] == null) {
                    types[parameter] = meta.getParameterType(position + 1);
                }
            }
            // Номер, который ни разу не встретился в тексте ($1 и $3 без $2), считаем строкой.
            this.parameterTypes = Arrays.stream(types).map(t -> t == null ? Types.VARCHAR : t).toList();
            this.columns = returnsRows ? SqlSession.columns(statement.getMetaData()) : List.of();
        }

        @Override
        public List<Integer> parameterTypes() {
            return parameterTypes;
        }

        @Override
        public List<Column> columns() {
            return columns;
        }

        @Override
        public StatementResult execute(List<Object> parameters) {
            checkParameterCount(parameters, placeholders.count());
            return transactions.statement(() -> transactions.withTransaction(() -> jdbc(() -> {
                for (int position = 0; position < placeholders.order().size(); position++) {
                    int parameter = placeholders.order().get(position);
                    Object value = parameters.get(parameter);
                    if (value == null) {
                        statement.setNull(position + 1, parameterTypes.get(parameter));
                    } else {
                        statement.setObject(position + 1, value);
                    }
                }
                return result(statement, statement.execute(), text);
            })));
        }

        @Override
        public void close() {
            jdbc(() -> {
                statement.close();
                return null;
            });
        }
    }

    private static void checkParameterCount(List<Object> parameters, int expected) {
        if (parameters.size() != expected) {
            throw new SwdbException(SqlStates.PROTOCOL_VIOLATION, "bind message supplies " + parameters.size()
                    + " parameters, but prepared statement requires " + expected);
        }
    }

    @Override
    public void close() {
        transactions.close();
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
        List<Column> columns = columns(rs.getMetaData());
        int count = columns.size();
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

    private static List<Column> columns(ResultSetMetaData meta) throws SQLException {
        int count = meta.getColumnCount();
        List<Column> columns = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            columns.add(new Column(meta.getColumnLabel(i), meta.getColumnType(i)));
        }
        return columns;
    }

    private static String normalize(String sql) {
        return ColumnPrimaryKey.toTableConstraint(calciteExplainSyntax(stripTrailingSemicolons(sql)));
    }

    private static String stripTrailingSemicolons(String sql) {
        String text = sql.strip();
        while (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1).strip();
        }
        return text;
    }

    /** PostgreSQL пишет {@code EXPLAIN SELECT ...}, а парсер Calcite ждёт {@code EXPLAIN PLAN FOR SELECT ...}. */
    private static String calciteExplainSyntax(String sql) {
        Matcher m = POSTGRES_EXPLAIN.matcher(sql);
        return m.matches() ? "EXPLAIN PLAN FOR " + m.group(1) : sql;
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
    private static SwdbException translate(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SwdbException swdb) {
                return swdb;
            }
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ArithmeticException arithmetic) {
                return arithmeticError(arithmetic, e);
            }
            if (t instanceof SqlParseException) {
                return new SwdbException(SqlStates.SYNTAX_ERROR, t.getMessage(), e);
            }
            if (t instanceof CalciteContextException) {
                return new SwdbException(validationSqlState(t.getCause()), t.getMessage(), e);
            }
        }
        return new SwdbException(SqlStates.INTERNAL_ERROR, e.getMessage(), e);
    }

    /**
     * Ошибка вычисления: деление на ноль или число, которое не помещается в тип
     * ({@code Value 12345.6 cannot be represented as a DECIMAL(5, 2)}).
     * Java пишет «/ by zero» для целых и «Division by zero» для BigDecimal.
     */
    private static SwdbException arithmeticError(ArithmeticException arithmetic, Throwable e) {
        String message = String.valueOf(arithmetic.getMessage());
        if (message.toLowerCase(Locale.ROOT).contains("by zero")) {
            return new SwdbException(SqlStates.DIVISION_BY_ZERO, "division by zero", e);
        }
        // Math.addExact и другие проверяемые операции Java пишут «integer overflow» / «long overflow».
        String postgresMessage = switch (message) {
            case "integer overflow" -> "integer out of range";
            case "long overflow" -> "bigint out of range";
            default -> message;
        };
        return new SwdbException(SqlStates.NUMERIC_VALUE_OUT_OF_RANGE, postgresMessage, e);
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
        // NULL в VALUES или пропущенная колонка без DEFAULT; в PostgreSQL это нарушение NOT NULL.
        if (message.matches("Column '.*' has no default value and does not allow NULLs")) {
            return SqlStates.NOT_NULL_VIOLATION;
        }
        return SqlStates.SYNTAX_ERROR_OR_ACCESS_RULE_VIOLATION;
    }
}
