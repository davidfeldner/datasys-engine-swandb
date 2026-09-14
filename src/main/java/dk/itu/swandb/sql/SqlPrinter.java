package dk.itu.swandb.sql;

import java.math.BigDecimal;
import java.util.StringJoiner;

import dk.itu.swandb.ColumnSpec;
import dk.itu.swandb.sql.ast.CopyStatement;
import dk.itu.swandb.sql.ast.CreateTableStatement;
import dk.itu.swandb.sql.ast.Predicate;
import dk.itu.swandb.sql.ast.SelectStatement;
import dk.itu.swandb.sql.ast.Statement;

/**
 * Renders a statement back to SQL text from the AST alone — the original
 * input text is never kept anywhere, which is what makes the printer
 * evidence that the parse captured everything. Output is normalized:
 * keywords and type names are uppercased and comments are gone, so the
 * guarantee is the round-trip property {@code parse(print(s)).equals(s)},
 * not a textual identity with the input.
 */
public final class SqlPrinter {

    /** Renders a statement back to SQL text that parses to an equal statement. */
    public String print(Statement s) {
        return switch (s) {
            case CreateTableStatement createTable -> printCreateTable(createTable);
            case CopyStatement copy -> printCopy(copy);
            case SelectStatement select -> printSelect(select);
        };
    }

    private static String printCreateTable(CreateTableStatement s) {
        // A table needs at least one column: 'CREATE TABLE x ();' is neither a
        // statement the grammar accepts nor one DuckDB accepts, so refuse to
        // emit it instead of printing SQL that cannot parse back.
        if (s.columns().isEmpty())
            throw new IllegalArgumentException(
                    "cannot print CREATE TABLE " + s.tableName() + ": a table needs at least one column");
        StringJoiner columns = new StringJoiner(", ");
        for (ColumnSpec column : s.columns()) {
            columns.add(column.name() + " " + column.type().name());
        }
        return "CREATE TABLE " + s.tableName() + " (" + columns + ");";
    }

    private static String printCopy(CopyStatement s) {
        return "COPY " + s.tableName() + " FROM " + stringLiteral(s.csvFilePath()) + ";";
    }

    private static String printSelect(SelectStatement s) {
        String sql = "SELECT * FROM " + s.tableName();
        if (s.where().isPresent()) sql += " WHERE " + printPredicate(s.where().get());
        return sql + ";";
    }

    private static String printPredicate(Predicate p) {
        return p.columnName() + " " + operator(p) + " " + literal(p.constant());
    }

    private static String operator(Predicate p) {
        return switch (p.comparison()) {
            case EQUALS -> "=";
            case LESS_THAN -> "<";
            case GREATER_THAN -> ">";
        };
    }

    /** The constant as a literal the grammar accepts back. */
    private static String literal(Object constant) {
        return switch (constant) {
            case null -> throw new IllegalArgumentException("predicate constant must not be null");
            case String str -> stringLiteral(str);
            case Long value -> value.toString();
            case Double value -> doubleLiteral(value);
            default -> throw new IllegalArgumentException(
                    "cannot print constant of type " + constant.getClass().getName());
        };
    }

    /**
     * A single-quoted literal that reads back as the same text. A literal ends
     * at the next quote on the same line and escaped quotes are out of scope,
     * so a value carrying a quote or a line break has no spelling in this
     * subset and is refused rather than printed wrong.
     */
    private static String stringLiteral(String value) {
        if (value.indexOf('\'') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)
            throw new IllegalArgumentException(
                    "cannot print string constant containing a quote or line break: " + value);
        return "'" + value + "'";
    }

    /**
     * {@code Double.toString} may emit scientific notation ({@code 1.0E10}),
     * which the literal rule does not match, so the value is rendered as a
     * plain decimal that always carries a decimal point — without it the
     * text would lex as a {@code LONG_LITERAL}. The rendering stays exact:
     * {@code BigDecimal.valueOf} starts from the shortest decimal that
     * identifies the double, the same digits {@code Double.toString} uses,
     * so parsing it back yields the same bits.
     */
    private static String doubleLiteral(double value) {
        if (!Double.isFinite(value))
            throw new IllegalArgumentException("cannot print non-finite constant: " + value);
        // Handles the sign of zero, which BigDecimal cannot represent.
        if (value == 0.0) return Double.toString(value);
        String text = BigDecimal.valueOf(value).toPlainString();
        return text.indexOf('.') < 0 ? text + ".0" : text;
    }
}
