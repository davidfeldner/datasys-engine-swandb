package dk.itu.swandb.sql;

/**
 * Thrown at the first syntax error of a script, instead of letting ANTLR
 * print the diagnostic and recover. Carries the error location in ANTLR's
 * conventions: a 1-based line and a 0-based column.
 */
public final class SqlParseException extends RuntimeException {

    private final int line;
    private final int column;

    public SqlParseException(String message, int line, int column) {
        super(message + " at line " + line + ":" + column);
        this.line = line;
        this.column = column;
    }

    public SqlParseException(String message, int line, int column, Throwable cause) {
        super(message + " at line " + line + ":" + column, cause);
        this.line = line;
        this.column = column;
    }

    /** 1-based line number of the offending token. */
    public int line() {
        return line;
    }

    /** 0-based column of the offending token, ANTLR's convention. */
    public int column() {
        return column;
    }
}
