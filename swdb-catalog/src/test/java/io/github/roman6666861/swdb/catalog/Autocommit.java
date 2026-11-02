package io.github.roman6666861.swdb.catalog;

import io.github.roman6666861.swdb.storage.KvTransaction;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Операции каталога для тестов: каждая в своей транзакции с COMMIT, как оператор вне BEGIN.
 * Каталог берётся при каждом вызове: тест может открыть его заново после «перезапуска».
 */
final class Autocommit {

    private final Supplier<Catalog> catalog;

    Autocommit(Supplier<Catalog> catalog) {
        this.catalog = catalog;
    }

    <T> T run(Function<KvTransaction, T> work) {
        try (KvTransaction tx = catalog.get().begin()) {
            T result = work.apply(tx);
            tx.commit();
            return result;
        }
    }

    Optional<TableStore> table(String name) {
        return run(tx -> catalog.get().table(tx, name));
    }

    List<TableStore> tables() {
        return run(tx -> catalog.get().tables(tx));
    }

    boolean createTable(String name, List<ColumnDef> columns, List<String> primaryKey, boolean ifNotExists) {
        return run(tx -> catalog.get().createTable(tx, name, columns, primaryKey, ifNotExists));
    }

    boolean dropTable(String name, boolean ifExists) {
        return run(tx -> catalog.get().dropTable(tx, name, ifExists));
    }

    boolean createIndex(String table, String name, List<String> columns, boolean unique, boolean ifNotExists) {
        return run(tx -> catalog.get().createIndex(tx, table, name, columns, unique, ifNotExists));
    }

    boolean createIndex(String table, String name, List<String> columns, boolean ifNotExists) {
        return createIndex(table, name, columns, false, ifNotExists);
    }

    boolean dropIndex(String name, boolean ifExists) {
        return run(tx -> catalog.get().dropIndex(tx, name, ifExists));
    }
}
