package io.github.roman6666861.swdb.server.pgwire;

import java.util.List;
import java.util.Map;

/**
 * Сообщения от клиента (frontend) в протоколе PostgreSQL v3.
 */
public sealed interface FrontendMessage {

    record SslRequest() implements FrontendMessage {
    }

    record GssEncRequest() implements FrontendMessage {
    }

    record CancelRequest() implements FrontendMessage {
    }

    record Startup(int protocolVersion, Map<String, String> parameters) implements FrontendMessage {
    }

    /** Simple query protocol: одна строка с SQL. */
    record Query(String sql) implements FrontendMessage {
    }

    // ---- extended query protocol: Parse → Bind → Describe → Execute → ... → Sync ----

    /** Что описать или закрыть: подготовленный оператор или портал. */
    enum Target {
        STATEMENT, PORTAL
    }

    /**
     * Разобрать SQL с параметрами {@code $1, $2, ...} и запомнить под именем ({@code ""} — безымянный).
     *
     * @param parameterTypes OID типов параметров, которые указал клиент; 0 — «выведи сам».
     *                       Их может быть меньше, чем параметров в SQL
     */
    record Parse(String statement, String sql, List<Integer> parameterTypes) implements FrontendMessage {
    }

    /**
     * Связать оператор со значениями параметров: получается портал, готовый к выполнению.
     *
     * @param parameterFormats формат каждого параметра (0 — текст, 1 — двоичный); пустой список —
     *                         все текстовые, один элемент — один формат для всех
     * @param parameters       значения в этом формате; {@code null} — SQL NULL
     * @param resultFormats    в каком формате вернуть колонки, по тем же правилам
     */
    record Bind(String portal, String statement, List<Integer> parameterFormats, List<byte[]> parameters,
                List<Integer> resultFormats) implements FrontendMessage {
    }

    record Describe(Target target, String name) implements FrontendMessage {
    }

    /** @param maxRows сколько строк вернуть; 0 — все */
    record Execute(String portal, int maxRows) implements FrontendMessage {
    }

    record Close(Target target, String name) implements FrontendMessage {
    }

    /** Отправить накопленные ответы, не дожидаясь Sync. */
    record Flush() implements FrontendMessage {
    }

    record Sync() implements FrontendMessage {
    }

    record Terminate() implements FrontendMessage {
    }

    /** Сообщение, которое сервер пока не поддерживает (COPY, вызов функции и т.д.). */
    record Unsupported(char type) implements FrontendMessage {
    }
}
