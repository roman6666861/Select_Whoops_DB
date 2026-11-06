package io.github.roman6666861.swdb.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class CrashRecoveryTest {

    private static final int CRASH_EXIT_CODE = 73;

    @TempDir
    Path dir;

    @Test
    void committedChangesSurviveHaltAndUncommittedChangesDisappear() throws Exception {
        Path data = dir.resolve("data");
        Path output = dir.resolve("child.log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(java, "--enable-native-access=ALL-UNNAMED",
                "-cp", System.getProperty("swdb.test.classpath"),
                CrashWriter.class.getName(), data.toString())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        try {
            assertThat(child.waitFor(30, TimeUnit.SECONDS))
                    .as("дочерний процесс должен завершиться за 30 секунд").isTrue();
            assertThat(child.exitValue())
                    .as("процесс должен дойти до halt; вывод: %s", Files.readString(output))
                    .isEqualTo(CRASH_EXIT_CODE);

            try (KvStore store = KvStore.open(data)) {
                assertThat(store.get(bytes("updated"))).isEqualTo(bytes("committed"));
                assertThat(store.get(bytes("inserted"))).isEqualTo(bytes("committed"));
                assertThat(store.get(bytes("deleted"))).isNull();
                assertThat(store.get(bytes("pending"))).isNull();
                List<String> keys = new ArrayList<>();
                try (KvIterator it = store.scan(new byte[0])) {
                    while (it.next()) {
                        keys.add(new String(it.key(), UTF_8));
                    }
                }
                assertThat(keys).containsExactly("inserted", "updated");
            }
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                assertThat(child.waitFor(10, TimeUnit.SECONDS))
                        .as("зависший дочерний процесс должен быть остановлен").isTrue();
            }
        }
    }

    /** Отдельная JVM: halt не вызывает close, finally и shutdown hooks. */
    public static final class CrashWriter {

        public static void main(String[] args) {
            KvStore store = KvStore.open(Path.of(args[0]));
            try (KvTransaction baseline = store.begin()) {
                baseline.put(bytes("updated"), bytes("old"));
                baseline.put(bytes("deleted"), bytes("old"));
                baseline.commit();
            }
            try (KvTransaction committed = store.begin()) {
                committed.put(bytes("updated"), bytes("committed"));
                committed.put(bytes("inserted"), bytes("committed"));
                committed.delete(bytes("deleted"));
                committed.commit();
            }
            KvTransaction pending = store.begin();
            pending.put(bytes("updated"), bytes("uncommitted"));
            pending.delete(bytes("inserted"));
            pending.put(bytes("pending"), bytes("uncommitted"));
            Runtime.getRuntime().halt(CRASH_EXIT_CODE);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(UTF_8);
    }
}
