package dk.itu.swandb.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import dk.itu.swandb.ColumnSpec;
import dk.itu.swandb.enums.ColumnType;
import dk.itu.swandb.enums.Comparison;
import dk.itu.swandb.sql.ast.CopyStatement;
import dk.itu.swandb.sql.ast.CreateTableStatement;
import dk.itu.swandb.sql.ast.Predicate;
import dk.itu.swandb.sql.ast.SelectStatement;
import dk.itu.swandb.sql.ast.Statement;
import dk.itu.swandb.sql.parser.SqlBaseVisitor;
import dk.itu.swandb.sql.parser.SqlParser;

/**
 * Builds the typed AST from an ANTLR parse tree. Literals are typed while
 * building: {@code 'Odense'} becomes a {@code String}, {@code 12} a
 * {@code Long} and {@code 23.5} a {@code Double}. Identifiers keep the
 * casing they were written with; keywords and type names do not, because
 * the AST stores the enum they denote, not the word.
 *
 * <p>A clean parse cannot fail here, so an unexpected child is a bug in the
 * grammar, not a user error, and surfaces as {@link IllegalStateException}.
 */
final class SqlAstBuilder extends SqlBaseVisitor<Object> {

    /** All statements of a script, in the order they were written. */
    @Override
    public Object visitScript(SqlParser.ScriptContext ctx) {
        List<Statement> statements = new ArrayList<>(ctx.statement().size());
        for (SqlParser.StatementContext statement : ctx.statement()) {
            statements.add((Statement) visit(statement));
        }
        return statements;
    }

    /** The single alternative this statement matched. */
    @Override
    public Object visitStatement(SqlParser.StatementContext ctx) {
        return visit(ctx.getChild(0));
    }

    @Override
    public Object visitCreateTable(SqlParser.CreateTableContext ctx) {
        List<ColumnSpec> columns = new ArrayList<>(ctx.columnDef().size());
        for (SqlParser.ColumnDefContext def : ctx.columnDef()) {
            columns.add((ColumnSpec) visit(def));
        }
        return new CreateTableStatement(ctx.IDENTIFIER().getText(), columns);
    }

    @Override
    public Object visitColumnDef(SqlParser.ColumnDefContext ctx) {
        return new ColumnSpec(ctx.IDENTIFIER().getText(), (ColumnType) visit(ctx.columnType()));
    }

    @Override
    public Object visitColumnType(SqlParser.ColumnTypeContext ctx) {
        // The lexer matched case-insensitively, but kept the text as written.
        return ColumnType.valueOf(ctx.getText().toUpperCase(Locale.ROOT));
    }

    @Override
    public Object visitCopy(SqlParser.CopyContext ctx) {
        return new CopyStatement(ctx.IDENTIFIER().getText(), unquote(ctx.STRING_LITERAL().getText()));
    }

    @Override
    public Object visitSelect(SqlParser.SelectContext ctx) {
        Optional<Predicate> where = Optional.empty();
        if (ctx.predicate() != null) where = Optional.of((Predicate) visit(ctx.predicate()));
        return new SelectStatement(ctx.IDENTIFIER().getText(), where);
    }

    @Override
    public Object visitPredicate(SqlParser.PredicateContext ctx) {
        return new Predicate(ctx.IDENTIFIER().getText(),
                comparison(ctx.comparison.getText()), visit(ctx.literal()));
    }

    @Override
    public Object visitLiteral(SqlParser.LiteralContext ctx) {
        if (ctx.STRING_LITERAL() != null) return unquote(ctx.STRING_LITERAL().getText());
        if (ctx.LONG_LITERAL() != null) return Long.valueOf(ctx.LONG_LITERAL().getText());
        if (ctx.DOUBLE_LITERAL() != null) return Double.valueOf(ctx.DOUBLE_LITERAL().getText());
        throw new IllegalStateException("literal rule matched no token: " + ctx.getText());
    }

    private static Comparison comparison(String operator) {
        return switch (operator) {
            case "=" -> Comparison.EQUALS;
            case "<" -> Comparison.LESS_THAN;
            case ">" -> Comparison.GREATER_THAN;
            default -> throw new IllegalStateException("unknown comparison operator: " + operator);
        };
    }

    /** Drop the surrounding single quotes; escaped quotes are out of scope. */
    private static String unquote(String literalText) {
        return literalText.substring(1, literalText.length() - 1);
    }
}
