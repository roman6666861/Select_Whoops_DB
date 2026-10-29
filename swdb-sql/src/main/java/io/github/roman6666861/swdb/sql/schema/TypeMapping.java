package io.github.roman6666861.swdb.sql.schema;

import io.github.roman6666861.swdb.catalog.ColumnDef;
import io.github.roman6666861.swdb.catalog.ColumnType;
import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
import io.github.roman6666861.swdb.storage.KeyBuilder;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.SqlBasicTypeNameSpec;
import org.apache.calcite.sql.SqlDataTypeSpec;
import org.apache.calcite.sql.type.SqlTypeName;

import java.util.Locale;

/**
 * Соответствие между SQL-типами из DDL, типами каталога и типами Calcite.
 */
public final class TypeMapping {

    /** Calcite хранит TIMESTAMP в миллисекундах, поэтому точнее 3 знаков не бывает. */
    private static final int MAX_TIMESTAMP_PRECISION = 3;

    private TypeMapping() {
    }

    /** Тип колонки из объявления в CREATE TABLE. */
    public static ColumnDef toColumnDef(String name, SqlDataTypeSpec spec) {
        String typeName = spec.getTypeName().getSimple().toUpperCase(Locale.ROOT);
        int precision = ColumnDef.UNSPECIFIED;
        int scale = ColumnDef.UNSPECIFIED;
        if (spec.getTypeNameSpec() instanceof SqlBasicTypeNameSpec basic) {
            precision = basic.getPrecision();
            scale = basic.getScale();
        }
        ColumnType type = switch (typeName) {
            case "BOOLEAN" -> ColumnType.BOOLEAN;
            case "INTEGER", "INT", "INT4" -> ColumnType.INTEGER;
            case "BIGINT", "INT8" -> ColumnType.BIGINT;
            case "DOUBLE", "FLOAT", "FLOAT8" -> ColumnType.DOUBLE;
            case "DECIMAL", "NUMERIC" -> ColumnType.DECIMAL;
            case "VARCHAR", "TEXT" -> ColumnType.VARCHAR;
            case "DATE" -> ColumnType.DATE;
            case "TIMESTAMP" -> ColumnType.TIMESTAMP;
            default -> throw new SwdbException(SqlStates.FEATURE_NOT_SUPPORTED,
                    "type " + typeName + " is not supported yet");
        };
        if (type == ColumnType.DECIMAL && precision == ColumnDef.UNSPECIFIED) {
            // Иначе Calcite подставит DECIMAL(19, 0) и молча округлит дробные значения.
            throw new SwdbException(SqlStates.FEATURE_NOT_SUPPORTED,
                    "DECIMAL requires precision, e.g. DECIMAL(10, 2)");
        }
        // Больше 38 цифр не умеют ни Calcite (SwdbTypeSystem), ни ключи (KeyBuilder.appendDecimal).
        if (type == ColumnType.DECIMAL && (precision < 1 || precision > KeyBuilder.MAX_DECIMAL_PRECISION)) {
            throw new SwdbException(SqlStates.INVALID_PARAMETER_VALUE, "NUMERIC precision " + precision
                    + " must be between 1 and " + KeyBuilder.MAX_DECIMAL_PRECISION);
        }
        if (type == ColumnType.TIMESTAMP) {
            precision = precision == ColumnDef.UNSPECIFIED
                    ? MAX_TIMESTAMP_PRECISION
                    : Math.min(precision, MAX_TIMESTAMP_PRECISION);
        }
        if (type != ColumnType.DECIMAL) {
            scale = ColumnDef.UNSPECIFIED;
        }
        boolean nullable = spec.getNullable() == null || spec.getNullable();
        return new ColumnDef(name, type, precision, scale, nullable);
    }

    /** Тип колонки для оптимизатора и исполнителя Calcite. */
    static RelDataType toRelType(RelDataTypeFactory factory, ColumnDef column) {
        RelDataType type = switch (column.type()) {
            case BOOLEAN -> factory.createSqlType(SqlTypeName.BOOLEAN);
            case INTEGER -> factory.createSqlType(SqlTypeName.INTEGER);
            case BIGINT -> factory.createSqlType(SqlTypeName.BIGINT);
            case DOUBLE -> factory.createSqlType(SqlTypeName.DOUBLE);
            case DECIMAL -> column.scale() == ColumnDef.UNSPECIFIED
                    ? factory.createSqlType(SqlTypeName.DECIMAL, column.precision())
                    : factory.createSqlType(SqlTypeName.DECIMAL, column.precision(), column.scale());
            // Длину VARCHAR(n) Calcite не сообщаем: перед записью он приводит значение к типу колонки,
            // а CAST к VARCHAR(n) молча обрезает строку. PostgreSQL в этом случае выдаёт ошибку,
            // поэтому длину проверяет RowCodec при записи.
            case VARCHAR -> factory.createSqlType(SqlTypeName.VARCHAR);
            case DATE -> factory.createSqlType(SqlTypeName.DATE);
            case TIMESTAMP -> factory.createSqlType(SqlTypeName.TIMESTAMP, column.precision());
        };
        return factory.createTypeWithNullability(type, column.nullable());
    }
}
