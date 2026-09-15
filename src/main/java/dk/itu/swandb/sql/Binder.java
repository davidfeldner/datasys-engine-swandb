package dk.itu.swandb.sql;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.swandb.ColumnSpec;
import dk.itu.swandb.StorageEngine;
import dk.itu.swandb.sql.ast.CopyStatement;
import dk.itu.swandb.sql.ast.CreateTableStatement;
import dk.itu.swandb.sql.ast.Predicate;
import dk.itu.swandb.sql.ast.SelectStatement;
import dk.itu.swandb.sql.ast.Statement;

/**
 * Validates a parsed statement against the engine's catalog: the table
 * exists, the referenced column exists, and a predicate constant has the
 * exact Java type its column demands. Everything that can be checked
 * without touching data is checked here, so execution (week 4) sees only
 * statements that make sense. The first violation throws
 * {@link IllegalArgumentException}, the same family as the engine API.
 */
public final class Binder {

    private static final Logger LOGGER = LoggerFactory.getLogger(Binder.class);

    private final StorageEngine engine;

    public Binder(StorageEngine engine) {
        if (engine == null)
            throw new IllegalArgumentException("engine must not be null");
        this.engine = engine;
    }

    /** Validates s against the catalog; throws IllegalArgumentException on the first violation. */
    public void bind(Statement s) {
        long start = System.nanoTime();
        try {
            switch (s) {
                case CreateTableStatement createTable -> bindCreateTable(createTable);
                case CopyStatement copy -> bindCopy(copy);
                case SelectStatement select -> bindSelect(select);
            }
            LOGGER.debug("statement={} durationMs={}", s.getClass().getSimpleName(), elapsedMs(start));
        } catch (IllegalArgumentException e) {
            LOGGER.error("statement={} failed reason={} durationMs={}",
                    s.getClass().getSimpleName(), e.getMessage().replace(',', ';'), elapsedMs(start));
            throw e;
        }
    }

    /**
     * Only the statement's own shape is validated here. Whether the table
     * already exists is left to execution, where the check can be made
     * atomically with the create.
     */
    private static void bindCreateTable(CreateTableStatement s) {
        if (s.columns().isEmpty())
            throw new IllegalArgumentException(
                    "CREATE TABLE " + s.tableName() + ": column list must not be empty");
        Set<String> seen = new HashSet<>();
        for (ColumnSpec column : s.columns()) {
            if (!seen.add(column.name()))
                throw new IllegalArgumentException(
                        "CREATE TABLE " + s.tableName() + ": duplicate column name: " + column.name());
        }
    }

    /** Whether the CSV file exists is execution's concern, the table is not. */
    private void bindCopy(CopyStatement s) {
        engine.schema(s.tableName());
    }

    private void bindSelect(SelectStatement s) {
        List<ColumnSpec> schema = engine.schema(s.tableName());
        if (s.where().isEmpty())
            return;

        Predicate predicate = s.where().get();
        ColumnSpec column = columnOrThrow(schema, predicate.columnName(), s.tableName());
        if (predicate.constant() == null)
            throw new IllegalArgumentException(
                    "constant must not be null for column " + column.name());
        if (!column.type().accepts(predicate.constant()))
            throw new IllegalArgumentException(
                    "constant type mismatch for column " + column.name()
                            + ": expected " + column.type()
                            + " got " + predicate.constant().getClass().getSimpleName());
    }

    private static ColumnSpec columnOrThrow(List<ColumnSpec> schema, String columnName, String tableName) {
        for (ColumnSpec column : schema) {
            if (column.name().equals(columnName))
                return column;
        }
        throw new IllegalArgumentException("unknown column: " + columnName + " in table " + tableName);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
