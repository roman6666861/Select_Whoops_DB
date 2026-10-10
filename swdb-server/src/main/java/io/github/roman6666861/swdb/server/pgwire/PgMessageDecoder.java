package io.github.roman6666861.swdb.server.pgwire;

import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Разбирает поток байт от клиента на {@link FrontendMessage}.
 * <p>
 * До StartupMessage сообщения не имеют байта типа: {@code Int32 length, Int32 code, body}.
 * После — {@code Byte1 type, Int32 length, body}; length включает себя, но не байт типа.
 */
public final class PgMessageDecoder extends ByteToMessageDecoder {

    private static final int SSL_REQUEST_CODE = 80877103;
    private static final int GSSENC_REQUEST_CODE = 80877104;
    private static final int CANCEL_REQUEST_CODE = 80877102;
    private static final int MAX_MESSAGE_LENGTH = 64 * 1024 * 1024;

    private boolean startupPhase = true;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (startupPhase) {
            decodeStartup(in, out);
        } else {
            decodeTyped(in, out);
        }
    }

    private void decodeStartup(ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 4) {
            return;
        }
        int length = in.getInt(in.readerIndex());
        checkLength(length, 8);
        if (in.readableBytes() < length) {
            return;
        }
        in.skipBytes(4);
        int code = in.readInt();
        ByteBuf body = in.readSlice(length - 8);
        out.add(switch (code) {
            case SSL_REQUEST_CODE -> new FrontendMessage.SslRequest();
            case GSSENC_REQUEST_CODE -> new FrontendMessage.GssEncRequest();
            case CANCEL_REQUEST_CODE -> new FrontendMessage.CancelRequest();
            default -> {
                startupPhase = false;
                yield new FrontendMessage.Startup(code, readParameters(body));
            }
        });
    }

    private void decodeTyped(ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 5) {
            return;
        }
        int length = in.getInt(in.readerIndex() + 1);
        checkLength(length, 4);
        if (in.readableBytes() < 1 + length) {
            return;
        }
        char type = (char) in.readByte();
        in.skipBytes(4);
        ByteBuf body = in.readSlice(length - 4);
        out.add(switch (type) {
            case 'Q' -> new FrontendMessage.Query(readCString(body));
            case 'S' -> new FrontendMessage.Sync();
            case 'X' -> new FrontendMessage.Terminate();
            default -> new FrontendMessage.Unsupported(type);
        });
    }

    private static Map<String, String> readParameters(ByteBuf body) {
        Map<String, String> parameters = new LinkedHashMap<>();
        while (body.isReadable() && body.getByte(body.readerIndex()) != 0) {
            parameters.put(readCString(body), readCString(body));
        }
        return parameters;
    }

    private static String readCString(ByteBuf buf) {
        int end = buf.bytesBefore((byte) 0);
        if (end < 0) {
            throw protocolViolation("unterminated string");
        }
        String value = buf.readCharSequence(end, UTF_8).toString();
        buf.skipBytes(1);
        return value;
    }

    private static void checkLength(int length, int min) {
        if (length < min || length > MAX_MESSAGE_LENGTH) {
            throw protocolViolation("invalid message length " + length);
        }
    }

    private static SwdbException protocolViolation(String message) {
        return new SwdbException(SqlStates.PROTOCOL_VIOLATION, message);
    }
}
