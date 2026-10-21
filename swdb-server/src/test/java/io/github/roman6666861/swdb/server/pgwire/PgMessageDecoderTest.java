package io.github.roman6666861.swdb.server.pgwire;

import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Разбор сообщений клиента: байты собираются так, как их отправляет драйвер PostgreSQL.
 */
class PgMessageDecoderTest {

    private EmbeddedChannel channel;

    @BeforeEach
    void startup() {
        channel = new EmbeddedChannel(new PgMessageDecoder());
        ByteBuf startup = Unpooled.buffer();
        startup.writeInt(0); // длина, допишем ниже
        startup.writeInt(196608); // протокол 3.0
        cstring(startup, "user");
        cstring(startup, "swdb");
        startup.writeByte(0);
        startup.setInt(0, startup.readableBytes());
        channel.writeInbound(startup);
        FrontendMessage message = channel.readInbound();
        assertThat(message).isInstanceOf(FrontendMessage.Startup.class);
    }

    @Test
    void parse() {
        FrontendMessage message = decode('P', b -> {
            cstring(b, "S_1");
            cstring(b, "SELECT name FROM t WHERE id = $1 AND v > $2");
            b.writeShort(2);
            b.writeInt(23); // int4
            b.writeInt(0);  // тип не указан
        });

        assertThat(message).isEqualTo(new FrontendMessage.Parse("S_1",
                "SELECT name FROM t WHERE id = $1 AND v > $2", List.of(23, 0)));
    }

    @Test
    void bindWithTextBinaryAndNullParameters() {
        FrontendMessage message = decode('B', b -> {
            cstring(b, "");    // безымянный портал
            cstring(b, "S_1");
            b.writeShort(2);   // форматы параметров
            b.writeShort(0);
            b.writeShort(1);
            b.writeShort(3);   // три параметра
            b.writeInt(2);
            b.writeBytes("42".getBytes(UTF_8));
            b.writeInt(4);
            b.writeInt(7);     // 7 в двоичном формате int4
            b.writeInt(-1);    // NULL
            b.writeShort(1);   // один формат для всех колонок
            b.writeShort(1);
        });

        assertThat(message).isInstanceOfSatisfying(FrontendMessage.Bind.class, bind -> {
            assertThat(bind.portal()).isEmpty();
            assertThat(bind.statement()).isEqualTo("S_1");
            assertThat(bind.parameterFormats()).containsExactly(0, 1);
            assertThat(bind.parameters()).hasSize(3);
            assertThat(bind.parameters().get(0)).isEqualTo("42".getBytes(UTF_8));
            assertThat(bind.parameters().get(1)).isEqualTo(new byte[]{0, 0, 0, 7});
            assertThat(bind.parameters().get(2)).isNull();
            assertThat(bind.resultFormats()).containsExactly(1);
        });
    }

    @Test
    void describeExecuteCloseFlush() {
        assertThat(decode('D', b -> {
            b.writeByte('S');
            cstring(b, "S_1");
        })).isEqualTo(new FrontendMessage.Describe(FrontendMessage.Target.STATEMENT, "S_1"));
        assertThat(decode('D', b -> {
            b.writeByte('P');
            cstring(b, "");
        })).isEqualTo(new FrontendMessage.Describe(FrontendMessage.Target.PORTAL, ""));
        assertThat(decode('E', b -> {
            cstring(b, "");
            b.writeInt(100);
        })).isEqualTo(new FrontendMessage.Execute("", 100));
        assertThat(decode('C', b -> {
            b.writeByte('P');
            cstring(b, "C_2");
        })).isEqualTo(new FrontendMessage.Close(FrontendMessage.Target.PORTAL, "C_2"));
        assertThat(decode('H', b -> {
        })).isEqualTo(new FrontendMessage.Flush());
    }

    @Test
    void malformedMessagesAreProtocolViolations() {
        // Bind обещает параметр длиной 10 байт, а в сообщении их нет.
        assertProtocolViolation(() -> decode('B', b -> {
            cstring(b, "");
            cstring(b, "");
            b.writeShort(0);
            b.writeShort(1);
            b.writeInt(10);
        }));
        assertProtocolViolation(() -> decode('D', b -> {
            b.writeByte('X');
            cstring(b, "");
        }));
        assertProtocolViolation(() -> decode('E', b -> {
            cstring(b, "");
            b.writeInt(0);
            b.writeByte(1); // лишний байт
        }));
    }

    private FrontendMessage decode(char type, Consumer<ByteBuf> body) {
        ByteBuf content = Unpooled.buffer();
        body.accept(content);
        ByteBuf message = Unpooled.buffer();
        message.writeByte(type);
        message.writeInt(4 + content.readableBytes());
        message.writeBytes(content);
        channel.writeInbound(message);
        return channel.readInbound();
    }

    private static void assertProtocolViolation(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(DecoderException.class)
                .cause().isInstanceOfSatisfying(SwdbException.class,
                        e -> assertThat(e.sqlState()).isEqualTo(SqlStates.PROTOCOL_VIOLATION));
    }

    private static void cstring(ByteBuf buf, String value) {
        buf.writeBytes(value.getBytes(UTF_8));
        buf.writeByte(0);
    }
}
