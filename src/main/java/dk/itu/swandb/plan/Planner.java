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
 * Turns a pinned table version plus an optional {@link Selection} into an
 * operator tree, and this is where partition pruning happens.
 *
 * <p>Since Exercise 7 the planner does not read the catalog: it is handed
 * the schema and the partition list of the version the statement pinned, so
 * nothing can change underneath a running statement. Every pruning decision
 * is still made from min/max summaries alone, before any data file is
 * opened. Partitions that may match survive into a {@link ScanOperator}; the
 * pruned ones never reach it. A {@code WHERE} wraps that scan in a
 * {@link FilterOperator}; no {@code WHERE} leaves a bare scan over every
 * partition.
 *
 * <p>The decision itself is {@link Pruning#decide}, the same answer
 * {@code DELETE} uses to choose its rewrite set, so the two can never
 * disagree about a partition.
 */
public final class Planner {

    private static final Logger LOGGER = LoggerFactory.getLogger(Planner.class);

    private final List<ColumnSpec> columns;
    private final List<Catalog.PartitionEntry> partitions;
    private final Path dataPath;

    /**
     * @param columns    the schema of the pinned version, in column order
     * @param partitions the partition list of the pinned version
     * @param dataPath   the table's single data file
     */
    public Planner(List<ColumnSpec> columns, List<Catalog.PartitionEntry> partitions, Path dataPath) {
        this.columns = List.copyOf(columns);
        this.partitions = List.copyOf(partitions);
        this.dataPath = dataPath;
    }

    /**
     * Plans a scan. An empty {@code where} reads every partition; a present
     * one is pruned per partition and then applied as a filter. Throws
     * {@link IllegalArgumentException} for an unknown column or a constant
     * whose type does not match the column.
     */
    public Plan plan(String tableName, Optional<Selection> where) {
        long start = System.nanoTime();
        try {
            Plan plan = where.isEmpty()
                    ? planBareScan(tableName)
                    : planFilteredScan(tableName, where.get());
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

    private Plan planBareScan(String tableName) {
        for (Catalog.PartitionEntry partition : partitions) {
            LOGGER.debug("table={} partition={} decision={}",
                    LogFormat.sanitize(tableName), partition.index(), "READ");
        }
        Operator scan = new ScanOperator(dataPath, columns, partitions);
        return new Plan(scan, new ScanStats(partitions.size(), partitions.size(), 0));
    }

    private Plan planFilteredScan(String tableName, Selection selection) {
        int columnIndex = columnIndex(selection.columnName());
        ColumnSpec filterColumn = columns.get(columnIndex);
        validateConstant(filterColumn, selection.constant());

        List<Catalog.PartitionEntry> survivors = new ArrayList<>();
        int pruned = 0;
        for (Catalog.PartitionEntry partition : partitions) {
            Catalog.ColumnSummary summary = summary(partition, selection.columnName());
            // Never prune on missing metadata: a partition that is never read
            // can never be recovered by the filter below, so an incomplete
            // catalog would silently drop matching rows. Fail loudly instead.
            if (summary == null || summary.min() == null || summary.max() == null)
                throw new IllegalStateException("missing min/max summary for column "
                        + selection.columnName() + " in partition " + partition.index()
                        + " of table " + tableName + "; refusing to prune on incomplete catalog metadata");
            Object min = summary.min();
            Object max = summary.max();
            Pruning.Decision decision = Pruning.decide(selection.comparison(), selection.constant(),
                    min, max, filterColumn.type());
            LOGGER.debug(
                    "table={} column={} comparison={} const={} partition={} min={} max={} decision={}",
                    LogFormat.sanitize(tableName), LogFormat.sanitize(selection.columnName()),
                    selection.comparison(), LogFormat.sanitize(selection.constant()),
                    partition.index(), LogFormat.sanitize(min), LogFormat.sanitize(max),
                    decision == Pruning.Decision.PROVABLY_FREE ? "PRUNED" : "READ");
            if (decision == Pruning.Decision.PROVABLY_FREE)
                pruned++;
            else
                survivors.add(partition);
        }

        Operator scan = new ScanOperator(dataPath, columns, survivors);
        Operator root = new FilterOperator(scan, columnIndex, filterColumn,
                selection.comparison(), selection.constant());
        return new Plan(root, new ScanStats(partitions.size(), survivors.size(), pruned));
    }

    /** Index of the column the selection names, or {@link IllegalArgumentException}. */
    public int columnIndex(String columnName) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(columnName))
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
