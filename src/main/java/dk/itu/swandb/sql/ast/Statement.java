package dk.itu.swandb.sql.ast;

/**
 * One parsed SQL statement. The AST speaks the storage engine's vocabulary
 * (see {@code dk.itu.swandb.ColumnSpec} and {@code dk.itu.swandb.enums}),
 * so binding and, in week 4, execution are near-trivial mappings.
 */
public sealed interface Statement
        permits CreateTableStatement, CopyStatement, SelectStatement {
}
