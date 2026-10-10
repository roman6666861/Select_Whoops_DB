package io.github.roman6666861.swdb.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class KvStoreTest {

    @TempDir
    Path dir;

    @Test
    void putGetDelete() {
        try (KvStore store = KvStore.open(dir)) {
            store.put(bytes("k"), bytes("v"));
            assertThat(store.get(bytes("k"))).isEqualTo(bytes("v"));

            store.delete(bytes("k"));
            assertThat(store.get(bytes("k"))).isNull();
        }
    }

    @Test
    void dataSurvivesReopen() {
        try (KvStore store = KvStore.open(dir)) {
            store.put(bytes("k"), bytes("v"));
        }
        try (KvStore store = KvStore.open(dir)) {
            assertThat(store.get(bytes("k"))).isEqualTo(bytes("v"));
        }
    }

    @Test
    void createsMissingParentDirectories() {
        try (KvStore store = KvStore.open(dir.resolve("a/b/data"))) {
            store.put(bytes("k"), bytes("v"));
            assertThat(store.get(bytes("k"))).isEqualTo(bytes("v"));
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(UTF_8);
    }
}
