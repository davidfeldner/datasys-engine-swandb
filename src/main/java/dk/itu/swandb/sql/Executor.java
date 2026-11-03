package dk.itu.swandb.sql;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import dk.itu.swandb.Result;
import dk.itu.swandb.StorageEngine;
import dk.itu.swandb.plan.Selection;
import dk.itu.swandb.sql.ast.CopyStatement;
import dk.itu.swandb.sql.ast.CreateTableStatement;
import dk.itu.swandb.sql.ast.DeleteStatement;
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
 *
 * <p>Each statement ends with one summary line naming the version it
 * committed or read:
 *
 * <pre>
 * statement=COPY table=trips rows=8 partitions=2 version=2 durationMs=12
 * statement=DELETE table=trips rowsDeleted=4 partitionsRewritten=1 partitionsCarried=3 version=3 durationMs=9
 * statement=SELECT table=trips rowsOut=4 partitionsRead=2 partitionsPruned=2 version=3 durationMs=3
 * </pre>
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
     * @return the statement's rows; empty for every statement but
     *         {@code SELECT}, which is the only one writing to stdout.
     */
    public List<Object[]> execute(Statement statement) {
        statementNumber++;
        MDC.put("statementNumber", String.valueOf(statementNumber));
        long start = System.nanoTime();
        try {
            binder.bind(statement);
            Result result = run(statement);
            logSummary(statement, result, elapsedMs(start));
            return result.rowsOut();
        } catch (RuntimeException e) {
            LOGGER.error("statement={} table={} failed reason={} durationMs={}",
                    kind(statement), table(statement),
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().replace(',', ';'),
                    elapsedMs(start));
            throw e;
        }
    }

    private Result run(Statement statement) {
        return switch (statement) {
            case CreateTableStatement createTable ->
                    engine.createTable(createTable.tableName(), createTable.columns());
            case CopyStatement copy -> engine.copyFile(copy.tableName(), copy.csvFilePath());
            case SelectStatement select -> {
                List<Object[]> rows = engine.select(select.tableName(), selection(select.where()));
                yield Result.selected(engine.lastReadVersion(), rows,
                        engine.lastPartitionsRead(), engine.lastPartitionsPruned());
            }
            case DeleteStatement delete -> engine.delete(delete.tableName(), selection(delete.where()));
        };
    }

    /** The AST's optional predicate as the storage-level selection. */
    private static java.util.Optional<Selection> selection(java.util.Optional<Predicate> where) {
        return where.map(p -> new Selection(p.columnName(), p.comparison(), p.constant()));
    }

    /** One line per statement, carrying the version it committed or read. */
    private static void logSummary(Statement statement, Result result, long durationMs) {
        switch (statement) {
            case CreateTableStatement s -> LOGGER.debug(
                    "statement={} table={} version={} durationMs={}",
                    kind(statement), s.tableName(), result.version(), durationMs);
            case CopyStatement s -> LOGGER.debug(
                    "statement={} table={} rows={} partitions={} version={} durationMs={}",
                    kind(statement), s.tableName(), result.rows(), result.partitions(),
                    result.version(), durationMs);
            case DeleteStatement s -> LOGGER.debug(
                    "statement={} table={} rowsDeleted={} partitionsRewritten={} partitionsCarried={} version={} durationMs={}",
                    kind(statement), s.tableName(), result.rows(), result.partitionsRewritten(),
                    result.partitionsCarried(), result.version(), durationMs);
            case SelectStatement s -> LOGGER.debug(
                    "statement={} table={} rowsOut={} partitionsRead={} partitionsPruned={} version={} durationMs={}",
                    kind(statement), s.tableName(), result.rows(), result.partitionsRead(),
                    result.partitionsPruned(), result.version(), durationMs);
        }
    }

    private static String kind(Statement statement) {
        return switch (statement) {
            case CreateTableStatement ignored -> "CREATE";
            case CopyStatement ignored -> "COPY";
            case SelectStatement ignored -> "SELECT";
            case DeleteStatement ignored -> "DELETE";
        };
    }

    private static String table(Statement statement) {
        return switch (statement) {
            case CreateTableStatement s -> s.tableName();
            case CopyStatement s -> s.tableName();
            case SelectStatement s -> s.tableName();
            case DeleteStatement s -> s.tableName();
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
