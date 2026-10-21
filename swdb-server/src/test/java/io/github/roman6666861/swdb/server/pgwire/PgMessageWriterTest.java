package io.github.roman6666861.swdb.server.pgwire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Байты ответов сервера: {@code Byte1 тип, Int32 длина (вместе с собой), тело}.
 */
class PgMessageWriterTest {

    @Test
    void emptyMessagesOfExtendedProtocol() {
        assertThat(hex(PgMessageWriter::parseComplete)).isEqualTo("3100000004");   // '1'
        assertThat(hex(PgMessageWriter::bindComplete)).isEqualTo("3200000004");    // '2'
        assertThat(hex(PgMessageWriter::closeComplete)).isEqualTo("3300000004");   // '3'
        assertThat(hex(PgMessageWriter::noData)).isEqualTo("6e00000004");          // 'n'
        assertThat(hex(PgMessageWriter::portalSuspended)).isEqualTo("7300000004"); // 's'
    }

    @Test
    void parameterDescription() {
        // 't', длина 4 + 2 + 2 × 4 = 14, два параметра: int4 (23) и text (25).
        assertThat(hex(w -> w.parameterDescription(List.of(23, 25))))
                .isEqualTo("74" + "0000000e" + "0002" + "00000017" + "00000019");
    }

    private static String hex(Consumer<PgMessageWriter> write) {
        ByteBuf buf = Unpooled.buffer();
        write.accept(new PgMessageWriter(buf));
        return ByteBufUtil.hexDump(buf);
    }
}
