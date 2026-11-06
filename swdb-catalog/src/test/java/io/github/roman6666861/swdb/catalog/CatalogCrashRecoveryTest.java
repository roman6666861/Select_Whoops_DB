package io.github.roman6666861.swdb.catalog;

import io.github.roman6666861.swdb.storage.KvStore;
import io.github.roman6666861.swdb.storage.KvTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogCrashRecoveryTest {

    private static final int CRASH_EXIT_CODE = 73;

    @TempDir
    Path dir;

    @Test
    void rowsAndSecondaryIndexRecoverTogetherAfterHalt() throws Exception {
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
                Catalog catalog = new Catalog(store);
                try (KvTransaction tx = catalog.begin()) {
                    TableStore table = catalog.openTable(tx, "items").orElseThrow();
                    assertThat(table.table().indexes()).hasSize(1);
                    IndexDef index = table.table().indexes().getFirst();
                    assertThat(index.name()).isEqualTo("items_value");
                    List<List<Object>> expected = List.of(
                            List.of(1, "new"), List.of(3, "shared"), List.of(4, "shared"));
                    assertThat(rows(table.scan(tx))).containsExactlyElementsOf(expected);
                    assertThat(rows(table.scanIndex(tx, index, null, null)))
                            .containsExactlyElementsOf(expected);
                    for (String absent : List.of("old", "deleted", "pending")) {
                        assertThat(rows(table.scanIndex(tx, index,
                                KeyBound.inclusive(absent), KeyBound.inclusive(absent))))
                                .as("в индексе не должно остаться значения %s", absent).isEmpty();
                    }
                    assertThat(rows(table.scanIndex(tx, index,
                            KeyBound.inclusive("new"), KeyBound.inclusive("new"))))
                            .containsExactly(List.of(1, "new"));
                    assertThat(rows(table.scanIndex(tx, index,
                            KeyBound.inclusive("shared"), KeyBound.inclusive("shared"))))
                            .containsExactly(List.of(3, "shared"), List.of(4, "shared"));
                }
            }
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                assertThat(child.waitFor(10, TimeUnit.SECONDS))
                        .as("зависший дочерний процесс должен быть остановлен").isTrue();
            }
        }
    }

    /** Авария после COMMIT и последующих незавершённых изменений строк и индекса. */
    public static final class CrashWriter {

        public static void main(String[] args) {
            KvStore store = KvStore.open(Path.of(args[0]));
            Catalog catalog = new Catalog(store);
            try (KvTransaction tx = catalog.begin()) {
                catalog.createTable(tx, "items", List.of(
                        new ColumnDef("id", ColumnType.INTEGER, -1, -1, false),
                        new ColumnDef("value", ColumnType.VARCHAR, 32, -1, false)),
                        List.of("id"), false);
                TableStore table = catalog.openTable(tx, "items").orElseThrow();
                table.insert(tx, new Object[]{1, "old"});
                table.insert(tx, new Object[]{2, "deleted"});
                table.insert(tx, new Object[]{4, "shared"});
                // Создание индекса по существующим строкам тоже должно пережить аварию.
                catalog.createIndex(tx, "items", "items_value", List.of("value"), false, false);
                tx.commit();
            }
            try (KvTransaction tx = catalog.begin()) {
                TableStore table = catalog.openTable(tx, "items").orElseThrow();
                table.update(tx, List.of(new TableStore.Change(
                        new Object[]{1, "old"}, new Object[]{1, "new"})));
                table.delete(tx, new Object[]{2, "deleted"});
                table.insert(tx, new Object[]{3, "shared"});
                tx.commit();
            }
            KvTransaction pending = catalog.begin();
            TableStore table = catalog.openTable(pending, "items").orElseThrow();
            table.update(pending, List.of(new TableStore.Change(
                    new Object[]{1, "new"}, new Object[]{1, "pending"})));
            table.delete(pending, new Object[]{3, "shared"});
            table.insert(pending, new Object[]{5, "pending"});
            Runtime.getRuntime().halt(CRASH_EXIT_CODE);
        }
    }

    private static List<List<Object>> rows(RowCursor cursor) {
        List<List<Object>> result = new ArrayList<>();
        try (cursor) {
            while (cursor.next()) {
                result.add(Arrays.asList(cursor.row()));
            }
        }
        return result;
    }
}
