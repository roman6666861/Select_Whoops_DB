package io.github.roman6666861.swdb.server.pgwire;

import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.ArrayList;
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
        out.add(readTyped(type, body));
    }

    private static FrontendMessage readTyped(char type, ByteBuf body) {
        FrontendMessage message;
        try {
            message = switch (type) {
                case 'Q' -> new FrontendMessage.Query(readCString(body));
                case 'P' -> new FrontendMessage.Parse(readCString(body), readCString(body), readInts(body));
                case 'B' -> readBind(body);
                case 'D' -> new FrontendMessage.Describe(readTarget(body), readCString(body));
                case 'E' -> new FrontendMessage.Execute(readCString(body), body.readInt());
                case 'C' -> new FrontendMessage.Close(readTarget(body), readCString(body));
                case 'H' -> new FrontendMessage.Flush();
                case 'S' -> new FrontendMessage.Sync();
                case 'X' -> new FrontendMessage.Terminate();
                default -> new FrontendMessage.Unsupported(type);
            };
        } catch (IndexOutOfBoundsException e) {
            // Поля сообщения обещают больше байт, чем в нём есть.
            throw protocolViolation("message '" + type + "' is too short");
        }
        if (body.isReadable() && !(message instanceof FrontendMessage.Unsupported)) {
            throw protocolViolation("unexpected " + body.readableBytes() + " bytes at the end of message '" + type + "'");
        }
        return message;
    }

    /**
     * {@code String portal, String statement, Int16 n, Int16[n] форматы параметров,
     * Int16 m, m × (Int32 длина или -1 для NULL, байты значения), Int16 k, Int16[k] форматы колонок}.
     */
    private static FrontendMessage.Bind readBind(ByteBuf body) {
        String portal = readCString(body);
        String statement = readCString(body);
        List<Integer> parameterFormats = readShorts(body);
        int count = body.readUnsignedShort();
        List<byte[]> parameters = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int length = body.readInt();
            if (length < -1) {
                throw protocolViolation("invalid parameter length " + length);
            }
            byte[] value = null;
            if (length >= 0) {
                value = new byte[length];
                body.readBytes(value);
            }
            parameters.add(value);
        }
        return new FrontendMessage.Bind(portal, statement, parameterFormats, parameters, readShorts(body));
    }

    /** {@code Int16 n, Int16[n]}. */
    private static List<Integer> readShorts(ByteBuf body) {
        int count = body.readUnsignedShort();
        List<Integer> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add((int) body.readShort());
        }
        return values;
    }

    /** {@code Int16 n, Int32[n]}. */
    private static List<Integer> readInts(ByteBuf body) {
        int count = body.readUnsignedShort();
        List<Integer> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(body.readInt());
        }
        return values;
    }

    /** {@code 'S'} — подготовленный оператор, {@code 'P'} — портал. */
    private static FrontendMessage.Target readTarget(ByteBuf body) {
        byte target = body.readByte();
        return switch (target) {
            case 'S' -> FrontendMessage.Target.STATEMENT;
            case 'P' -> FrontendMessage.Target.PORTAL;
            default -> throw protocolViolation("invalid describe/close target '" + (char) target + "'");
        };
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
