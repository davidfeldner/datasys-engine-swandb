package dk.itu.swandb.sql;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.swandb.ColumnSpec;
import dk.itu.swandb.StorageEngine;
import dk.itu.swandb.plan.ColumnResolver;
import dk.itu.swandb.plan.ColumnResolver.ResolvedColumn;
import dk.itu.swandb.sql.ast.CopyStatement;
import dk.itu.swandb.sql.ast.CreateTableStatement;
import dk.itu.swandb.sql.ast.DeleteStatement;
import dk.itu.swandb.sql.ast.JoinPredicate;
import dk.itu.swandb.sql.ast.Predicate;
import dk.itu.swandb.sql.ast.SelectStatement;
import dk.itu.swandb.sql.ast.Statement;

/**
 * Validates a parsed statement against the engine's catalog: the table
 * exists, the referenced column exists, and a predicate constant has the
 * exact Java type its column demands. Everything that can be checked
 * without touching data is checked here, so execution sees only statements
 * that make sense. The first violation throws {@link IllegalArgumentException},
 * the same family as the engine API.
 *
 * <p>Since Exercise 10 a {@code SELECT} may name several tables. Column
 * names stay unqualified, so every name must occur in exactly one table of
 * the {@code FROM} list; the shared {@link ColumnResolver} answers which one,
 * and rejects unknown and ambiguous names. A join predicate must connect two
 * different tables whose columns have the same type, because the hash join
 * compares keys with Java {@code equals}.
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
                case DeleteStatement delete -> bindDelete(delete);
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
        List<List<ColumnSpec>> schemas = new ArrayList<>(s.tableNames().size());
        for (String tableName : s.tableNames()) {
            schemas.add(engine.schema(tableName));
        }
        ColumnResolver resolver = new ColumnResolver(s.tableNames(), schemas);

        for (Predicate filter : s.filters()) {
            bindFilter(resolver, filter);
        }
        for (JoinPredicate join : s.joins()) {
            bindJoin(resolver, join);
        }
    }

    /** A DELETE needs a table to delete from; whether it has rows is execution's business. */
    private void bindDelete(DeleteStatement s) {
        ColumnResolver resolver = new ColumnResolver(List.of(s.tableName()),
                List.of(engine.schema(s.tableName())));
        if (s.where().isPresent())
            bindFilter(resolver, s.where().get());
    }

    /** The column must resolve to exactly one table, and the constant must have its exact type. */
    private static void bindFilter(ColumnResolver resolver, Predicate predicate) {
        ResolvedColumn resolved = resolver.resolve(predicate.columnName());
        ColumnSpec column = resolved.column();
        if (predicate.constant() == null)
            throw new IllegalArgumentException(
                    "constant must not be null for column " + column.name());
        if (!column.type().accepts(predicate.constant()))
            throw new IllegalArgumentException(
                    "constant type mismatch for column " + column.name()
                            + ": expected " + column.type()
                            + " got " + predicate.constant().getClass().getSimpleName());
    }

    /**
     * Both sides must resolve to different tables and to columns of the same
     * type. The type rule matters because the hash join compares keys with
     * {@code equals}: a mismatched join would silently return no rows.
     */
    private static void bindJoin(ColumnResolver resolver, JoinPredicate join) {
        ResolvedColumn left = resolver.resolve(join.leftColumn());
        ResolvedColumn right = resolver.resolve(join.rightColumn());

        if (left.tableIndex() == right.tableIndex())
            throw new IllegalArgumentException("join sides must be in different tables: "
                    + left.column().name() + " and " + right.column().name()
                    + " both belong to table " + left.tableName());
        if (left.column().type() != right.column().type())
            throw new IllegalArgumentException("join column type mismatch: "
                    + left.column().name() + " is " + left.column().type() + " but "
                    + right.column().name() + " is " + right.column().type());
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
