package dk.itu.swandb.plan;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.swandb.Catalog;
import dk.itu.swandb.ColumnSpec;
import dk.itu.swandb.LogFormat;
import dk.itu.swandb.Pruning;
import dk.itu.swandb.ScanStats;
import dk.itu.swandb.enums.Comparison;

/**
 * Turns a table name plus an optional {@link Selection} into an operator
 * tree, and this is where partition pruning now happens.
 *
 * <p>The catalog is the single source of truth for min/max summaries
 * ({@code docs/storage-design.md}), so every pruning decision is made from
 * catalog metadata alone, before any data file is opened. Partitions that
 * may match survive into a {@link ScanOperator}; the pruned ones never
 * reach it. A {@code WHERE} wraps that scan in a {@link FilterOperator}; no
 * {@code WHERE} leaves a bare scan over every partition.
 *
 * <p>Filling {@link ScanStats} and emitting the {@code decision=READ|PRUNED}
 * lines here, rather than in the storage engine, is the visible half of the
 * refactor.
 */
public final class Planner {

    private static final Logger LOGGER = LoggerFactory.getLogger(Planner.class);

    private final Catalog catalog;
    private final Path dataDir;

    public Planner(Catalog catalog, Path dataDir) {
        this.catalog = catalog;
        this.dataDir = dataDir;
    }

    /**
     * Plans a scan of {@code tableName}. An empty {@code where} reads every
     * partition; a present one is pruned per partition and then applied as a
     * filter. Throws {@link IllegalArgumentException} for an unknown table or
     * column, or a constant whose type does not match the column.
     */
    public Plan plan(String tableName, Optional<Selection> where) {
        long start = System.nanoTime();
        try {
            Catalog.TableEntry table = catalog.getTable(tableName);
            Plan plan = where.isEmpty()
                    ? planBareScan(table)
                    : planFilteredScan(table, where.get());
            LOGGER.debug("table={} partitionsTotal={} partitionsRead={} partitionsPruned={} durationMs={}",
                    LogFormat.sanitize(tableName), plan.stats().partitionsTotal(),
                    plan.stats().partitionsRead(), plan.stats().partitionsPruned(),
                    elapsedMs(start));
            return plan;
        } catch (RuntimeException e) {
            LOGGER.debug("table={} error={} durationMs={}",
                    LogFormat.sanitize(tableName), e.getClass().getSimpleName(), elapsedMs(start));
            throw e;
        }
    }

    private Plan planBareScan(Catalog.TableEntry table) {
        List<Catalog.PartitionEntry> partitions = table.partitions;
        for (Catalog.PartitionEntry partition : partitions) {
            LOGGER.debug("table={} partition={} decision={}",
                    LogFormat.sanitize(table.name), partition.index(), "READ");
        }
        Operator scan = new ScanOperator(dataPath(table), table.columns, partitions);
        return new Plan(scan, new ScanStats(partitions.size(), partitions.size(), 0));
    }

    private Plan planFilteredScan(Catalog.TableEntry table, Selection selection) {
        int columnIndex = columnIndex(table, selection.columnName());
        ColumnSpec filterColumn = table.columns.get(columnIndex);
        validateConstant(filterColumn, selection.constant());

        List<Catalog.PartitionEntry> survivors = new ArrayList<>();
        int pruned = 0;
        for (Catalog.PartitionEntry partition : table.partitions) {
            Catalog.ColumnSummary summary = summary(partition, selection.columnName());
            Object min = summary == null ? null : summary.min();
            Object max = summary == null ? null : summary.max();
            boolean canPrune = Pruning.canPrune(selection.comparison(), selection.constant(),
                    min, max, filterColumn.type());
            LOGGER.debug(
                    "table={} column={} comparison={} const={} partition={} min={} max={} decision={}",
                    LogFormat.sanitize(table.name), LogFormat.sanitize(selection.columnName()),
                    selection.comparison(), LogFormat.sanitize(selection.constant()),
                    partition.index(), LogFormat.sanitize(min), LogFormat.sanitize(max),
                    canPrune ? "PRUNED" : "READ");
            if (canPrune)
                pruned++;
            else
                survivors.add(partition);
        }

        Operator scan = new ScanOperator(dataPath(table), table.columns, survivors);
        Operator root = new FilterOperator(scan, columnIndex, filterColumn,
                selection.comparison(), selection.constant());
        return new Plan(root, new ScanStats(table.partitions.size(), survivors.size(), pruned));
    }

    private Path dataPath(Catalog.TableEntry table) {
        return dataDir.resolve(table.dataFile);
    }

    private static int columnIndex(Catalog.TableEntry table, String columnName) {
        for (int i = 0; i < table.columns.size(); i++) {
            if (table.columns.get(i).name().equals(columnName))
                return i;
        }
        throw new IllegalArgumentException("unknown column: " + columnName);
    }

    private static Catalog.ColumnSummary summary(Catalog.PartitionEntry partition, String columnName) {
        for (Catalog.ColumnSummary summary : partition.columns) {
            if (summary.name().equals(columnName))
                return summary;
        }
        return null;
    }

    private static void validateConstant(ColumnSpec column, Object constant) {
        if (constant == null)
            throw new IllegalArgumentException("constant must not be null");
        if (!column.type().accepts(constant))
            throw new IllegalArgumentException(
                    "constant type mismatch for column " + column.name()
                            + ": expected " + column.type() + " got " + constant.getClass().getSimpleName());
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
