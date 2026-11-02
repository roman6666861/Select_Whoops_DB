package io.github.roman6666861.swdb.sql;

import io.github.roman6666861.swdb.catalog.Catalog;
import io.github.roman6666861.swdb.catalog.ColumnDef;
import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
import io.github.roman6666861.swdb.sql.schema.SessionTransaction;
import io.github.roman6666861.swdb.sql.schema.SwdbSchema;
import io.github.roman6666861.swdb.sql.schema.TypeMapping;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.ddl.SqlColumnDeclaration;
import org.apache.calcite.sql.ddl.SqlCreateTable;
import org.apache.calcite.sql.ddl.SqlDropTable;
import org.apache.calcite.sql.ddl.SqlKeyConstraint;

import java.util.ArrayList;
import java.util.List;

/**
 * Выполняет DDL: переводит разобранные Calcite операторы CREATE/DROP TABLE и наши
 * {@link IndexCommand} в операции каталога.
 */
final class DdlExecutor {

    private final Catalog catalog;
    private final SessionTransaction transaction;

    /** @param transaction транзакция сессии, в которой выполняется DDL-оператор */
    DdlExecutor(Catalog catalog, SessionTransaction transaction) {
        this.catalog = catalog;
        this.transaction = transaction;
    }

    StatementResult createTable(SqlCreateTable create) {
        if (create.getReplace()) {
            throw unsupported("CREATE OR REPLACE TABLE");
        }
        if (create.query != null) {
            throw unsupported("CREATE TABLE ... AS SELECT");
        }
        if (create.columnList == null) {
            throw new SwdbException(SqlStates.INVALID_TABLE_DEFINITION, "table must have columns");
        }
        List<ColumnDef> columns = new ArrayList<>();
        List<String> primaryKey = new ArrayList<>();
        List<SqlKeyConstraint> uniques = new ArrayList<>();
        for (SqlNode element : create.columnList) {
            switch (element) {
                case SqlColumnDeclaration column -> {
                    if (column.expression != null) {
                        throw unsupported("DEFAULT and generated columns");
                    }
                    columns.add(TypeMapping.toColumnDef(column.name.getSimple(), column.dataType));
                }
                case SqlKeyConstraint key when key.getOperator().getKind() == SqlKind.PRIMARY_KEY -> {
                    if (!primaryKey.isEmpty()) {
                        throw new SwdbException(SqlStates.INVALID_TABLE_DEFINITION,
                                "multiple primary keys for table are not allowed");
                    }
                    primaryKey.addAll(keyColumns(key));
                }
                case SqlKeyConstraint key when key.getOperator().getKind() == SqlKind.UNIQUE -> uniques.add(key);
                default -> throw unsupported("constraint " + element);
            }
        }
        String table = tableName(create.name);
        if (catalog.createTable(transaction.current(), table, columns, primaryKey, create.ifNotExists)) {
            // UNIQUE — это уникальный индекс, как и в PostgreSQL. Он создаётся в той же транзакции:
            // если, например, его имя занято, не появится ни индекса, ни самой таблицы.
            for (SqlKeyConstraint unique : uniques) {
                List<String> uniqueColumns = keyColumns(unique);
                SqlIdentifier constraintName = (SqlIdentifier) unique.getOperandList().get(0);
                String name = constraintName != null ? constraintName.getSimple()
                        : freeName(table + "_" + String.join("_", uniqueColumns) + "_key");
                catalog.createIndex(transaction.current(), table, name, uniqueColumns, true, false);
            }
        }
        return new StatementResult.Update("CREATE TABLE", 0);
    }

    /** Колонки {@code PRIMARY KEY (...)} или {@code UNIQUE (...)}; первый операнд — имя ограничения. */
    private static List<String> keyColumns(SqlKeyConstraint key) {
        List<String> columns = new ArrayList<>();
        for (SqlNode column : (SqlNodeList) key.getOperandList().get(1)) {
            columns.add(((SqlIdentifier) column).getSimple());
        }
        return columns;
    }

    StatementResult dropTable(SqlDropTable drop) {
        catalog.dropTable(transaction.current(), tableName(drop.name), drop.ifExists);
        return new StatementResult.Update("DROP TABLE", 0);
    }

    StatementResult createIndex(IndexCommand.Create create) {
        String name = create.name() != null ? create.name()
                : freeName(create.table() + "_" + String.join("_", create.columns()) + "_idx");
        catalog.createIndex(transaction.current(), create.table(), name, create.columns(), create.unique(),
                create.ifNotExists());
        return new StatementResult.Update("CREATE INDEX", 0);
    }

    StatementResult dropIndex(IndexCommand.Drop drop) {
        catalog.dropIndex(transaction.current(), drop.name(), drop.ifExists());
        return new StatementResult.Update("DROP INDEX", 0);
    }

    /**
     * Имя по умолчанию, как в PostgreSQL: {@code users_email_idx} для индекса, {@code users_email_key}
     * для ограничения UNIQUE, а если имя занято — {@code users_email_idx1} и дальше.
     */
    private String freeName(String base) {
        String name = base;
        for (int i = 1; catalog.relationExists(transaction.current(), name); i++) {
            name = base + i;
        }
        return name;
    }

    /** {@code users} или {@code public.users}; другие схемы пока не поддерживаются. */
    private static String tableName(SqlIdentifier name) {
        if (name.isSimple()) {
            return name.getSimple();
        }
        if (name.names.size() == 2 && name.names.getFirst().equals(SwdbSchema.NAME)) {
            return name.names.get(1);
        }
        throw unsupported("schemas other than \"" + SwdbSchema.NAME + "\"");
    }

    private static SwdbException unsupported(String feature) {
        return new SwdbException(SqlStates.FEATURE_NOT_SUPPORTED, feature + " is not supported yet");
    }
}
