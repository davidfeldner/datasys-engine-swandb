package dk.itu.swandb.sql.ast;

import java.util.Objects;

/**
 * {@code COPY table FROM 'file.csv'}. The path is the literal's content
 * without its quotes; whether that file exists is execution's concern.
 */
public record CopyStatement(String tableName, String csvFilePath) implements Statement {

    public CopyStatement {
        Objects.requireNonNull(tableName, "tableName");
        Objects.requireNonNull(csvFilePath, "csvFilePath");
    }
}
