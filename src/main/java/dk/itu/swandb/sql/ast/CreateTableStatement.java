package dk.itu.swandb.sql.ast;

import java.util.List;
import java.util.Objects;

import dk.itu.swandb.ColumnSpec;

/**
 * {@code CREATE TABLE name (column TYPE, ...)}. The column names keep the
 * casing they were written with, and the types reuse the engine's
 * {@link ColumnSpec} rather than a parallel type of their own.
 */
public record CreateTableStatement(String tableName, List<ColumnSpec> columns)
        implements Statement {

    public CreateTableStatement {
        Objects.requireNonNull(tableName, "tableName");
        columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
    }
}
