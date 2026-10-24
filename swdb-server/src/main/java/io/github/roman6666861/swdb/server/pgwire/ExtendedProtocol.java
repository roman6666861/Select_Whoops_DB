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

    private final SqlSession session;
    private final Map<String, PreparedQuery> statements = new HashMap<>();
    private final Map<String, Portal> portals = new HashMap<>();

    ExtendedProtocol(SqlSession session) {
        this.session = session;
    }

    /** Портал: оператор, значения параметров и, после первого Execute, результат с позицией чтения. */
    private static final class Portal {

        final String statement;
        final PreparedQuery query;
        final List<Object> parameters;
        StatementResult result;
        int sentRows;

        Portal(String statement, PreparedQuery query, List<Object> parameters) {
            this.statement = statement;
            this.query = query;
            this.parameters = parameters;
        }
    }

    void parse(FrontendMessage.Parse parse, PgMessageWriter w) {
        String name = parse.statement();
        if (!name.isEmpty() && statements.containsKey(name)) {
            throw new SwdbException(SqlStates.DUPLICATE_PREPARED_STATEMENT,
                    "prepared statement \"" + name + "\" already exists");
        }
        PreparedQuery query = session.prepare(parse.sql());
        closeStatement(name);
        statements.put(name, query);
        w.parseComplete();
    }

    void bind(FrontendMessage.Bind bind, PgMessageWriter w) {
        String name = bind.portal();
        if (!name.isEmpty() && portals.containsKey(name)) {
            throw new SwdbException(SqlStates.DUPLICATE_CURSOR, "cursor \"" + name + "\" already exists");
        }
        PreparedQuery query = statement(bind.statement());
        List<Integer> types = query.parameterTypes();
        if (bind.parameters().size() != types.size()) {
            throw new SwdbException(SqlStates.PROTOCOL_VIOLATION, "bind message supplies " + bind.parameters().size()
                    + " parameters, but prepared statement \"" + bind.statement() + "\" requires " + types.size());
        }
        List<Object> values = new ArrayList<>(types.size());
        for (int i = 0; i < types.size(); i++) {
            byte[] value = bind.parameters().get(i);
            if (format(bind.parameterFormats(), i, types.size()) != TEXT) {
                throw new SwdbException(SqlStates.FEATURE_NOT_SUPPORTED, "binary parameter format is not supported yet");
            }
            values.add(value == null ? null : PgType.fromText(new String(value, StandardCharsets.UTF_8), types.get(i)));
        }
        int columns = query.columns().size();
        for (int i = 0; i < columns; i++) {
            if (format(bind.resultFormats(), i, columns) != TEXT) {
                throw new SwdbException(SqlStates.FEATURE_NOT_SUPPORTED, "binary result format is not supported yet");
            }
        }
        portals.put(name, new Portal(bind.statement(), query, values));
        w.bindComplete();
    }

    void describe(FrontendMessage.Describe describe, PgMessageWriter w) {
        List<Column> columns;
        if (describe.target() == FrontendMessage.Target.STATEMENT) {
            PreparedQuery query = statement(describe.name());
            w.parameterDescription(query.parameterTypes().stream().map(type -> PgType.of(type).oid()).toList());
            columns = query.columns();
        } else {
            columns = portal(describe.name()).query.columns();
        }
        if (columns.isEmpty()) {
            w.noData();
        } else {
            w.rowDescription(columns);
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
                    w.dataRow(row);
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
        PreparedQuery query = statements.remove(name);
        if (query != null) {
            for (Iterator<Portal> it = portals.values().iterator(); it.hasNext(); ) {
                if (it.next().statement.equals(name)) {
                    it.remove();
                }
            }
            query.close();
        }
    }

    private PreparedQuery statement(String name) {
        PreparedQuery query = statements.get(name);
        if (query == null) {
            throw new SwdbException(SqlStates.INVALID_SQL_STATEMENT_NAME,
                    "prepared statement \"" + name + "\" does not exist");
        }
        return query;
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
