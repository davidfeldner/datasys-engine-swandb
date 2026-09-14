package dk.itu.swandb.sql;

import java.util.List;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.swandb.sql.ast.Statement;
import dk.itu.swandb.sql.parser.SqlLexer;
import dk.itu.swandb.sql.parser.SqlParser.ScriptContext;

/**
 * Front door of the SQL front end: SQL text in, typed AST out. The grammar
 * lives in {@code src/main/antlr4/dk/itu/swandb/sql/parser/Sql.g4}; this
 * class only wires the generated lexer and parser to {@link SqlAstBuilder},
 * with error reporting replaced by {@link SqlParseException}.
 */
public final class SqlParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(SqlParser.class);

    /**
     * Makes the first syntax error fatal. ANTLR otherwise prints the
     * diagnostic to stderr and tries to recover, which would hand the
     * builder a half-broken tree.
     */
    private static final BaseErrorListener ERROR_LISTENER = new BaseErrorListener() {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                int line, int charPositionInLine, String msg, RecognitionException e) {
            throw new SqlParseException(msg, line, charPositionInLine, e);
        }
    };

    /** Parses a whole script of ';'-terminated statements. */
    public List<Statement> parse(String sqlText) {
        if (sqlText == null)
            throw new IllegalArgumentException("sqlText must not be null");

        long start = System.nanoTime();
        try {
            List<Statement> statements = parseOrThrow(sqlText);
            LOGGER.debug("statements={} durationMs={}", statements.size(), elapsedMs(start));
            return statements;
        } catch (SqlParseException e) {
            LOGGER.error("failed line={} col={} durationMs={}", e.line(), e.column(), elapsedMs(start));
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Statement> parseOrThrow(String sqlText) {
        SqlLexer lexer = new SqlLexer(CharStreams.fromString(sqlText));
        lexer.removeErrorListeners();
        lexer.addErrorListener(ERROR_LISTENER);

        // Fully qualified on purpose: the generated parser shares this
        // class's simple name, so it cannot be imported.
        dk.itu.swandb.sql.parser.SqlParser parser =
                new dk.itu.swandb.sql.parser.SqlParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(ERROR_LISTENER);

        ScriptContext script = parser.script();
        return (List<Statement>) new SqlAstBuilder().visitScript(script);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
