package io.github.roman6666861.swdb.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.roman6666861.swdb.storage.KvStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ключи записей вторичного индекса: {@code 'I' [tableId] [indexId] [значения колонок] [pk]}.
 */
class IndexKeysTest {

    /** {@code users(id INT PRIMARY KEY, email VARCHAR(20), age INT)}, id таблицы 7. */
    private static final TableDef USERS = new TableDef(7, "users", List.of(
            new ColumnDef("id", ColumnType.INTEGER, -1, -1, false),
            new ColumnDef("email", ColumnType.VARCHAR, 20, -1, true),
            new ColumnDef("age", ColumnType.INTEGER, -1, -1, true)), List.of(0));
    private static final IndexDef BY_EMAIL = new IndexDef(1, "users_email", List.of(1));
    private static final IndexDef BY_AGE_EMAIL = new IndexDef(2, "users_age_email", List.of(2, 1));

    private final RowCodec codec = new RowCodec(USERS.withIndex(BY_EMAIL).withIndex(BY_AGE_EMAIL));

    @TempDir
    Path dir;

    @Test
    void keyLayout() {
        assertThat(hex(codec.encodeIndexKey(BY_EMAIL, new Object[]{5, "bob", 30}))).isEqualTo(
                "49" + "80000007" + "80000001"    // 'I', таблица 7, индекс 1
                        + "01" + "626f62" + "0001" // не NULL, 'bob', конец строки
                        + "80000005");             // первичный ключ 5
        // NULL — один байт 00 вместо значения.
        assertThat(hex(codec.encodeIndexKey(BY_EMAIL, new Object[]{5, null, 30})))
                .isEqualTo("49" + "80000007" + "80000001" + "00" + "80000005");
    }

    @Test
    void entriesAreOrderedByValueThenPrimaryKey() {
        Object[][] rows = {{4, "bob", 1}, {1, "carol", 1}, {3, "ann", 1}, {2, "bob", 1}, {9, null, 1}};
        List<byte[]> keys = new ArrayList<>();
        for (Object[] row : rows) {
            keys.add(codec.encodeIndexKey(BY_EMAIL, row));
        }
        // RocksDB сравнивает ключи как беззнаковые байты.
        keys.sort(Arrays::compareUnsigned);

        assertThat(keys).containsExactly(
                codec.encodeIndexKey(BY_EMAIL, new Object[]{9, null, 1}),   // NULL раньше всех значений
                codec.encodeIndexKey(BY_EMAIL, new Object[]{3, "ann", 1}),
                codec.encodeIndexKey(BY_EMAIL, new Object[]{2, "bob", 1}),   // одинаковые значения —
                codec.encodeIndexKey(BY_EMAIL, new Object[]{4, "bob", 1}),   // в порядке первичного ключа
                codec.encodeIndexKey(BY_EMAIL, new Object[]{1, "carol", 1}));
    }

    @Test
    void prefixSelectsEntriesWithLeadingValues() {
        byte[] bob = codec.encodeIndexPrefix(BY_EMAIL, List.of("bob"));

        assertThat(startsWith(codec.encodeIndexKey(BY_EMAIL, new Object[]{2, "bob", 1}), bob)).isTrue();
        // Строки кодируются с концом «00 01», поэтому 'bob' — не префикс 'bobby'.
        assertThat(startsWith(codec.encodeIndexKey(BY_EMAIL, new Object[]{2, "bobby", 1}), bob)).isFalse();

        // Составной индекс (age, email): префикс по первой колонке.
        byte[] age30 = codec.encodeIndexPrefix(BY_AGE_EMAIL, List.of(30));
        assertThat(startsWith(codec.encodeIndexKey(BY_AGE_EMAIL, new Object[]{1, "x", 30}), age30)).isTrue();
        assertThat(startsWith(codec.encodeIndexKey(BY_AGE_EMAIL, new Object[]{1, "x", 31}), age30)).isFalse();
        // Записи разных индексов одной таблицы не пересекаются.
        assertThat(startsWith(codec.encodeIndexKey(BY_EMAIL, new Object[]{1, "x", 30}),
                Keys.indexPrefix(7, BY_AGE_EMAIL.id()))).isFalse();
    }

    @Test
    void indexesAreStoredInTableDescription() throws Exception {
        ObjectMapper json = new ObjectMapper();
        TableDef withIndex = USERS.withIndex(BY_EMAIL);

        assertThat(json.readValue(json.writeValueAsBytes(withIndex), TableDef.class)).isEqualTo(withIndex);
        assertThat(withIndex.withoutIndex("users_email")).isEqualTo(USERS);
    }

    @Test
    void tableSavedBeforeIndexesExistedStillLoads() {
        // Описание в том виде, в каком его записывали прошлые версии: без поля indexes.
        String old = """
                {"id":1,"name":"t","columns":[{"name":"id","type":"INTEGER","precision":-1,"scale":-1,
                "nullable":false}],"primaryKey":[0]}""";
        try (KvStore store = KvStore.open(dir)) {
            store.put(Keys.tableMeta(1), old.getBytes(StandardCharsets.UTF_8));

            TableDef table = new Catalog(store).table("t").orElseThrow().table();

            assertThat(table.indexes()).isEmpty();
            assertThat(table.primaryKey()).containsExactly(0);
        }
    }

    private static boolean startsWith(byte[] key, byte[] prefix) {
        return key.length >= prefix.length && Arrays.equals(key, 0, prefix.length, prefix, 0, prefix.length);
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
