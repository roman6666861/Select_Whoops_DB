package io.github.roman6666861.swdb.server.pgwire;

import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
import io.github.roman6666861.swdb.sql.SqlEngine;
import io.github.roman6666861.swdb.sql.SqlSession;
import io.github.roman6666861.swdb.sql.StatementResult;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Обслуживает одно клиентское подключение.
 * <p>
 * Сообщения обрабатываются строго по очереди: каждое ставится в цепочку {@link CompletableFuture}
 * на виртуальных потоках, поэтому долгий запрос не блокирует event loop Netty,
 * а состояние сессии не нужно синхронизировать.
 */
public final class PgConnectionHandler extends SimpleChannelInboundHandler<FrontendMessage> {

    private static final Logger log = LoggerFactory.getLogger(PgConnectionHandler.class);
    private static final AtomicInteger PROCESS_IDS = new AtomicInteger();

    private final SqlEngine engine;
    private final Executor executor;
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);

    // Доступны только из задач цепочки tail.
    private SqlSession session;
    private boolean skipUntilSync;

    public PgConnectionHandler(SqlEngine engine, Executor executor) {
        this.engine = engine;
        this.executor = executor;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FrontendMessage message) {
        switch (message) {
            // TLS и GSSAPI пока не поддерживаются: отвечаем 'N', клиент продолжит без шифрования.
            case FrontendMessage.SslRequest _, FrontendMessage.GssEncRequest _ ->
                    ctx.writeAndFlush(ctx.alloc().buffer(1).writeByte('N'));
            case FrontendMessage.CancelRequest _ -> ctx.close();
            default -> runSerially(ctx, () -> handle(ctx, message));
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        runSerially(ctx, () -> {
            if (session != null) {
                session.close();
                session = null;
            }
        });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.debug("Connection {} failed", ctx.channel().remoteAddress(), cause);
        sendErrorAndClose(ctx, cause);
    }

    private void runSerially(ChannelHandlerContext ctx, Runnable task) {
        tail = tail.thenRunAsync(task, executor).exceptionally(e -> {
            log.error("Unexpected error on connection {}", ctx.channel().remoteAddress(), e);
            sendErrorAndClose(ctx, e);
            return null;
        });
    }

    private void handle(ChannelHandlerContext ctx, FrontendMessage message) {
        switch (message) {
            case FrontendMessage.Startup startup -> startup(ctx, startup);
            case FrontendMessage.Query query -> query(ctx, query.sql());
            case FrontendMessage.Sync _ -> {
                skipUntilSync = false;
                send(ctx, w -> w.readyForQuery(PgMessageWriter.IDLE));
            }
            case FrontendMessage.Terminate _ -> ctx.close();
            case FrontendMessage.Unsupported unsupported -> {
                // После ошибки в extended protocol клиент ждёт ReadyForQuery только на Sync.
                if (!skipUntilSync) {
                    skipUntilSync = true;
                    send(ctx, w -> w.errorResponse(SqlStates.FEATURE_NOT_SUPPORTED,
                            "message type '" + unsupported.type() + "' is not supported yet"));
                }
            }
            case FrontendMessage.SslRequest _, FrontendMessage.GssEncRequest _, FrontendMessage.CancelRequest _ ->
                    throw new IllegalStateException("handled on event loop: " + message);
        }
    }

    private void startup(ChannelHandlerContext ctx, FrontendMessage.Startup startup) {
        int major = startup.protocolVersion() >>> 16;
        if (major != 3) {
            sendErrorAndClose(ctx, new SwdbException(SqlStates.PROTOCOL_VIOLATION,
                    "unsupported frontend protocol " + major + "." + (startup.protocolVersion() & 0xFFFF)));
            return;
        }
        session = engine.openSession();
        String applicationName = startup.parameters().getOrDefault("application_name", "");
        log.debug("Client connected: {} {}", ctx.channel().remoteAddress(), startup.parameters());
        send(ctx, w -> w
                .authenticationOk()
                .parameterStatus("server_version", "16.0")
                .parameterStatus("server_encoding", "UTF8")
                .parameterStatus("client_encoding", "UTF8")
                .parameterStatus("DateStyle", "ISO, MDY")
                .parameterStatus("TimeZone", "UTC")
                .parameterStatus("integer_datetimes", "on")
                .parameterStatus("standard_conforming_strings", "on")
                .parameterStatus("application_name", applicationName)
                .backendKeyData(PROCESS_IDS.incrementAndGet(), ThreadLocalRandom.current().nextInt())
                .readyForQuery(PgMessageWriter.IDLE));
    }

    private void query(ChannelHandlerContext ctx, String sql) {
        StatementResult result;
        try {
            result = session.execute(sql);
        } catch (SwdbException e) {
            send(ctx, w -> w
                    .errorResponse(e.sqlState(), e.getMessage())
                    .readyForQuery(PgMessageWriter.IDLE));
            return;
        }
        send(ctx, w -> {
            switch (result) {
                case StatementResult.Rows rows -> {
                    w.rowDescription(rows.columns());
                    for (List<Object> row : rows.rows()) {
                        w.dataRow(row);
                    }
                    w.commandComplete("SELECT " + rows.rows().size());
                }
                case StatementResult.Update update -> w.commandComplete(commandTag(update));
                case StatementResult.Empty _ -> w.emptyQueryResponse();
            }
            w.readyForQuery(PgMessageWriter.IDLE);
        });
    }

    private static String commandTag(StatementResult.Update update) {
        return switch (update.command()) {
            case "INSERT" -> "INSERT 0 " + update.count();
            case "UPDATE", "DELETE", "MERGE" -> update.command() + " " + update.count();
            default -> update.command();
        };
    }

    private static void sendErrorAndClose(ChannelHandlerContext ctx, Throwable cause) {
        SwdbException error = findSwdbException(cause);
        String sqlState = error != null ? error.sqlState() : SqlStates.INTERNAL_ERROR;
        String message = error != null ? error.getMessage() : String.valueOf(cause.getMessage());
        ByteBuf buf = ctx.alloc().buffer();
        new PgMessageWriter(buf).errorResponse(sqlState, message);
        ctx.writeAndFlush(buf).addListener(_ -> ctx.close());
    }

    private static SwdbException findSwdbException(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof SwdbException e) {
                return e;
            }
        }
        return null;
    }

    private static void send(ChannelHandlerContext ctx, Consumer<PgMessageWriter> messages) {
        ByteBuf buf = ctx.alloc().buffer();
        try {
            messages.accept(new PgMessageWriter(buf));
        } catch (RuntimeException e) {
            buf.release();
            throw e;
        }
        ctx.writeAndFlush(buf);
    }
}
