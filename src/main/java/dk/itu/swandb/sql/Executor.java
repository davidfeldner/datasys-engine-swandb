package dk.itu.swandb.sql;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import dk.itu.swandb.StorageEngine;
import dk.itu.swandb.sql.ast.CopyStatement;
import dk.itu.swandb.sql.ast.CreateTableStatement;
import dk.itu.swandb.sql.ast.Predicate;
import dk.itu.swandb.sql.ast.SelectStatement;
import dk.itu.swandb.sql.ast.Statement;

/**
 * Runs a script statement by statement: parse (once, for the whole script),
 * then bind and execute each statement in turn, stopping at the first error.
 *
 * <p>The session is one run of the engine over one script. The executor owns
 * the statement counter: it increments before each statement's bind, puts the
 * number in the MDC, and leaves it there for the whole statement, so every
 * log line a statement writes — binder, planner, engine — shares it. The
 * first statement is 1; script-level parsing and the engine start/stop lines
 * stay at the 0 set at startup.
 */
public final class Executor {

    private static final Logger LOGGER = LoggerFactory.getLogger(Executor.class);

    private final StorageEngine engine;
    private final Binder binder;
    private final SqlParser parser;
    private int statementNumber;

    public Executor(StorageEngine engine) {
        if (engine == null)
            throw new IllegalArgumentException("engine must not be null");
        this.engine = engine;
        this.binder = new Binder(engine);
        this.parser = new SqlParser();
    }

    /**
     * Parses and runs a whole script. Each {@code SELECT}'s rows are handed
     * to {@code onRows} as soon as its statement finishes, so output keeps
     * script order and a later error cannot retract earlier rows.
     */
    public void executeScript(String sqlText, RowSink onRows) {
        if (onRows == null)
            throw new IllegalArgumentException("onRows must not be null");
        List<Statement> statements = parser.parse(sqlText);
        for (Statement statement : statements) {
            onRows.accept(execute(statement));
        }
    }

    /**
     * Binds and executes one statement inside its own MDC statement number.
     *
     * @return the statement's rows; empty for {@code CREATE TABLE} and
     *         {@code COPY}, which have no operator tree.
     */
    public List<Object[]> execute(Statement statement) {
        statementNumber++;
        MDC.put("statementNumber", String.valueOf(statementNumber));
        long start = System.nanoTime();
        try {
            binder.bind(statement);
            List<Object[]> rows = run(statement);
            LOGGER.debug("statement={} table={} rowsOut={} durationMs={}",
                    kind(statement), table(statement), rows.size(), elapsedMs(start));
            return rows;
        } catch (RuntimeException e) {
            LOGGER.error("statement={} table={} failed reason={} durationMs={}",
                    kind(statement), table(statement),
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().replace(',', ';'),
                    elapsedMs(start));
            throw e;
        }
    }

    private List<Object[]> run(Statement statement) {
        return switch (statement) {
            case CreateTableStatement createTable -> {
                engine.createTable(createTable.tableName(), createTable.columns());
                yield List.of();
            }
            case CopyStatement copy -> {
                engine.copyFile(copy.tableName(), copy.csvFilePath());
                yield List.of();
            }
            case SelectStatement select -> executeSelect(select);
        };
    }

    private List<Object[]> executeSelect(SelectStatement select) {
        if (select.where().isEmpty())
            return engine.selectAll(select.tableName());
        Predicate predicate = select.where().get();
        return engine.select(select.tableName(), predicate.columnName(),
                predicate.comparison(), predicate.constant());
    }

    private static String kind(Statement statement) {
        return switch (statement) {
            case CreateTableStatement ignored -> "CREATE";
            case CopyStatement ignored -> "COPY";
            case SelectStatement ignored -> "SELECT";
        };
    }

    private static String table(Statement statement) {
        return switch (statement) {
            case CreateTableStatement s -> s.tableName();
            case CopyStatement s -> s.tableName();
            case SelectStatement s -> s.tableName();
        };
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** Receives each statement's rows as the script runs. */
    @FunctionalInterface
    public interface RowSink {
        void accept(List<Object[]> rows);
    }
}
