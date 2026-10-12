package io.github.roman6666861.swdb.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

    @Test
    void scanReturnsOnlyPrefixInKeyOrder() {
        try (KvStore store = KvStore.open(dir)) {
            store.put(bytes("a1"), bytes("x"));
            store.put(bytes("b2"), bytes("2"));
            store.put(bytes("b1"), bytes("1"));
            store.put(bytes("c1"), bytes("y"));

            assertThat(scanKeys(store, "b")).containsExactly("b1", "b2");
        }
    }

    @Test
    void scanRangeIncludesFromAndExcludesTo() {
        try (KvStore store = KvStore.open(dir)) {
            for (String key : List.of("a", "b", "b1", "c", "d")) {
                store.put(bytes(key), bytes("v"));
            }

            assertThat(scanKeys(store.scan(bytes("b"), bytes("d")))).containsExactly("b", "b1", "c");
            assertThat(scanKeys(store.scan(bytes("bb"), bytes("c")))).isEmpty();
            assertThat(scanKeys(store.scan(bytes("c"), null))).containsExactly("c", "d");
        }
    }

    @Test
    void batchIsAppliedTogether() {
        try (KvStore store = KvStore.open(dir)) {
            store.put(bytes("t1"), bytes("1"));
            store.put(bytes("t2"), bytes("2"));
            store.put(bytes("u1"), bytes("3"));

            store.write(batch -> batch
                    .deleteRange(bytes("t"), bytes("u"))
                    .put(bytes("meta"), bytes("dropped")));

            assertThat(scanKeys(store, "t")).isEmpty();
            assertThat(store.get(bytes("u1"))).isEqualTo(bytes("3"));
            assertThat(store.get(bytes("meta"))).isEqualTo(bytes("dropped"));
        }
    }

    private static List<String> scanKeys(KvStore store, String prefix) {
        return scanKeys(store.scan(bytes(prefix)));
    }

    private static List<String> scanKeys(KvIterator iterator) {
        List<String> keys = new ArrayList<>();
        try (KvIterator it = iterator) {
            while (it.next()) {
                keys.add(new String(it.key(), UTF_8));
            }
        }
        return keys;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(UTF_8);
    }
}
