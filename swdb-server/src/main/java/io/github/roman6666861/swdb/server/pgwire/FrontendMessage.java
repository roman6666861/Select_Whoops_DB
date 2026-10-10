package io.github.roman6666861.swdb.server.pgwire;

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

    record Sync() implements FrontendMessage {
    }

    record Terminate() implements FrontendMessage {
    }

    /** Сообщение, которое сервер пока не поддерживает (extended query protocol, COPY и т.д.). */
    record Unsupported(char type) implements FrontendMessage {
    }
}
