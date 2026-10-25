package io.github.roman6666861.swdb.server.pgwire;

import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
import io.github.roman6666861.swdb.sql.Column;
import io.github.roman6666861.swdb.sql.PreparedQuery;
import io.github.roman6666861.swdb.sql.SqlSession;
import io.github.roman6666861.swdb.sql.StatementResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Extended query protocol одного подключения: подготовленные операторы и порталы.
 * <ul>
 *   <li>{@code Parse} готовит оператор ({@link SqlSession#prepare}) и запоминает под именем;</li>
 *   <li>{@code Bind} соединяет его со значениями параметров — получается портал;</li>
 *   <li>{@code Describe} сообщает типы параметров и колонки результата;</li>
 *   <li>{@code Execute} выполняет портал и отдаёт строки, при желании клиента — порциями.</li>
 * </ul>
 * Безымянные ({@code ""}) оператор и портал заменяются следующими {@code Parse} и {@code Bind};
 * именованные живут до {@code Close} или конца сессии (порталы — до конца транзакции).
 * <p>
 * Каждый метод пишет ответы в переданный {@link PgMessageWriter} или бросает {@link SwdbException};
 * тогда {@link PgConnectionHandler} отвечает ошибкой и пропускает сообщения до {@code Sync}.
 */
final class ExtendedProtocol {

    private static final int TEXT = 0;
    private static final int BINARY = 1;

    private final SqlSession session;
    private final Map<String, Statement> statements = new HashMap<>();
    private final Map<String, Portal> portals = new HashMap<>();

    ExtendedProtocol(SqlSession session) {
        this.session = session;
    }

    /**
     * Подготовленный оператор и OID типов его параметров: какие указал клиент в Parse,
     * а где он прислал 0 — выведенные Calcite. По этим OID читаются двоичные значения в Bind.
     */
    private record Statement(PreparedQuery query, List<Integer> parameterOids) {
    }

    /** Портал: оператор, значения параметров и, после первого Execute, результат с позицией чтения. */
    private static final class Portal {

        final String statement;
        final PreparedQuery query;
        final List<Object> parameters;
        /** Для каждой колонки — в двоичном ли формате её просил клиент. */
        final List<Boolean> binaryColumns;
        StatementResult result;
        int sentRows;

        Portal(String statement, PreparedQuery query, List<Object> parameters, List<Boolean> binaryColumns) {
            this.statement = statement;
            this.query = query;
            this.parameters = parameters;
            this.binaryColumns = binaryColumns;
        }
    }

    void parse(FrontendMessage.Parse parse, PgMessageWriter w) {
        String name = parse.statement();
        if (!name.isEmpty() && statements.containsKey(name)) {
            throw new SwdbException(SqlStates.DUPLICATE_PREPARED_STATEMENT,
                    "prepared statement \"" + name + "\" already exists");
        }
        PreparedQuery query = session.prepare(parse.sql());
        List<Integer> types = query.parameterTypes();
        List<Integer> oids = new ArrayList<>(types.size());
        for (int i = 0; i < types.size(); i++) {
            Integer clientOid = i < parse.parameterTypes().size() ? parse.parameterTypes().get(i) : 0;
            oids.add(clientOid != 0 ? clientOid : PgType.of(types.get(i)).oid());
        }
        closeStatement(name);
        statements.put(name, new Statement(query, List.copyOf(oids)));
        w.parseComplete();
    }

    void bind(FrontendMessage.Bind bind, PgMessageWriter w) {
        String name = bind.portal();
        if (!name.isEmpty() && portals.containsKey(name)) {
            throw new SwdbException(SqlStates.DUPLICATE_CURSOR, "cursor \"" + name + "\" already exists");
        }
        Statement statement = statement(bind.statement());
        List<Integer> types = statement.query().parameterTypes();
        if (bind.parameters().size() != types.size()) {
            throw new SwdbException(SqlStates.PROTOCOL_VIOLATION, "bind message supplies " + bind.parameters().size()
                    + " parameters, but prepared statement \"" + bind.statement() + "\" requires " + types.size());
        }
        List<Object> values = new ArrayList<>(types.size());
        for (int i = 0; i < types.size(); i++) {
            byte[] value = bind.parameters().get(i);
            if (value == null) {
                values.add(null);
            } else if (format(bind.parameterFormats(), i, types.size()) == BINARY) {
                // Байты читаются по типу клиента, затем значение приводится к типу параметра Calcite.
                values.add(PgType.toParameter(PgBinary.decode(value, statement.parameterOids().get(i)), types.get(i)));
            } else {
                values.add(PgType.fromText(new String(value, StandardCharsets.UTF_8), types.get(i)));
            }
        }
        int columns = statement.query().columns().size();
        List<Boolean> binaryColumns = new ArrayList<>(columns);
        for (int i = 0; i < columns; i++) {
            binaryColumns.add(format(bind.resultFormats(), i, columns) == BINARY);
        }
        portals.put(name, new Portal(bind.statement(), statement.query(), values, List.copyOf(binaryColumns)));
        w.bindComplete();
    }

    void describe(FrontendMessage.Describe describe, PgMessageWriter w) {
        if (describe.target() == FrontendMessage.Target.STATEMENT) {
            Statement statement = statement(describe.name());
            w.parameterDescription(statement.parameterOids());
            // Форматы колонок станут известны только в Bind, поэтому здесь они текстовые, как в PostgreSQL.
            describeColumns(statement.query().columns(), List.of(), w);
        } else {
            Portal portal = portal(describe.name());
            describeColumns(portal.query.columns(), portal.binaryColumns, w);
        }
    }

    private static void describeColumns(List<Column> columns, List<Boolean> binary, PgMessageWriter w) {
        if (columns.isEmpty()) {
            w.noData();
        } else {
            w.rowDescription(columns, binary);
        }
    }

    /** Строки — без RowDescription: его клиент получает на Describe. */
    void execute(FrontendMessage.Execute execute, PgMessageWriter w) {
        Portal portal = portal(execute.portal());
        if (portal.result == null) {
            portal.result = portal.query.execute(portal.parameters);
        }
        switch (portal.result) {
            case StatementResult.Rows rows -> {
                int total = rows.rows().size();
                int end = execute.maxRows() > 0 ? Math.min(total, portal.sentRows + execute.maxRows()) : total;
                for (List<Object> row : rows.rows().subList(portal.sentRows, end)) {
                    w.dataRow(row, rows.columns(), portal.binaryColumns);
                }
                int sent = end - portal.sentRows;
                portal.sentRows = end;
                if (end < total) {
                    w.portalSuspended(); // клиент попросит ещё следующим Execute
                } else {
                    w.commandComplete("SELECT " + sent);
                }
            }
            case StatementResult.Update update -> w.commandComplete(PgConnectionHandler.commandTag(update));
            case StatementResult.Empty _ -> w.emptyQueryResponse();
        }
    }

    void close(FrontendMessage.Close close, PgMessageWriter w) {
        // Закрыть несуществующее — не ошибка.
        if (close.target() == FrontendMessage.Target.STATEMENT) {
            closeStatement(close.name());
        } else {
            portals.remove(close.name());
        }
        w.closeComplete();
    }

    /**
     * Конец пачки сообщений. Порталы живут до конца транзакции: вне блока BEGIN транзакция
     * каждого оператора уже закончилась, и порталы больше не нужны.
     */
    void sync(boolean inTransaction) {
        if (!inTransaction) {
            portals.clear();
        }
    }

    /** Simple query заменяет безымянные оператор и портал, как в PostgreSQL. */
    void simpleQuery() {
        closeStatement("");
        portals.remove("");
    }

    /** Закрывает оператор и порталы, созданные из него. */
    private void closeStatement(String name) {
        Statement statement = statements.remove(name);
        if (statement != null) {
            for (Iterator<Portal> it = portals.values().iterator(); it.hasNext(); ) {
                if (it.next().statement.equals(name)) {
                    it.remove();
                }
            }
            statement.query().close();
        }
    }

    private Statement statement(String name) {
        Statement statement = statements.get(name);
        if (statement == null) {
            throw new SwdbException(SqlStates.INVALID_SQL_STATEMENT_NAME,
                    "prepared statement \"" + name + "\" does not exist");
        }
        return statement;
    }

    private Portal portal(String name) {
        Portal portal = portals.get(name);
        if (portal == null) {
            throw new SwdbException(SqlStates.INVALID_CURSOR_NAME, "portal \"" + name + "\" does not exist");
        }
        return portal;
    }

    /**
     * Формат i-го значения: список пуст — все текстовые, один элемент — общий для всех,
     * иначе — по элементу на значение.
     */
    private static int format(List<Integer> formats, int index, int count) {
        if (formats.isEmpty()) {
            return TEXT;
        }
        if (formats.size() == 1) {
            return formats.getFirst();
        }
        if (formats.size() != count) {
            throw new SwdbException(SqlStates.PROTOCOL_VIOLATION,
                    "expected " + count + " format codes, got " + formats.size());
        }
        return formats.get(index);
    }
}
