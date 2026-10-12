package io.github.roman6666861.swdb.sql;

import io.github.roman6666861.swdb.catalog.Catalog;
import io.github.roman6666861.swdb.catalog.ColumnDef;
import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
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
 * Выполняет DDL: переводит разобранные Calcite операторы CREATE/DROP TABLE в операции каталога.
 */
final class DdlExecutor {

    private final Catalog catalog;

    DdlExecutor(Catalog catalog) {
        this.catalog = catalog;
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
                    for (SqlNode keyColumn : (SqlNodeList) key.getOperandList().get(1)) {
                        primaryKey.add(((SqlIdentifier) keyColumn).getSimple());
                    }
                }
                default -> throw unsupported("constraint " + element);
            }
        }
        catalog.createTable(tableName(create.name), columns, primaryKey, create.ifNotExists);
        return new StatementResult.Update("CREATE TABLE", 0);
    }

    StatementResult dropTable(SqlDropTable drop) {
        catalog.dropTable(tableName(drop.name), drop.ifExists);
        return new StatementResult.Update("DROP TABLE", 0);
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
