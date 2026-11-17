package dk.itu.swandb.plan;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.swandb.Catalog;
import dk.itu.swandb.ColumnSpec;
import dk.itu.swandb.LogFormat;
import dk.itu.swandb.Pruning;
import dk.itu.swandb.ScanStats;
import dk.itu.swandb.plan.ColumnResolver.ResolvedColumn;

/**
 * Turns pinned table versions plus the statement's filters and join
 * predicates into an operator tree. This is where partition pruning happens.
 *
 * <p>One pipeline is built per table, exactly as in Part 1: a
 * {@link ScanOperator} over the partitions that survive pruning, wrapped in
 * one {@link FilterOperator} per filter bound to that table. Pruning still
 * works per table because each filter binds to exactly one table.
 *
 * <p>The {@code FROM} list is then folded left to right. For each new table
 * the planner finds the join predicate that connects it to the tables
 * already joined and adds
 * {@code HashJoinOperator(joinedSoFar, newTable, buildKey, probeKey)}: the
 * intermediate result is the build side and the new table probes. That
 * order is deliberately naive — week 11 optimizes it — and because output
 * rows are build columns followed by probe columns, {@code SELECT *} emits
 * columns in {@code FROM}-list order.
 *
 * <p><b>More than one connecting predicate.</b> A table may be connected to
 * the tables before it by at most one join predicate. If a query supplies a
 * second one, the planner rejects it with an {@link IllegalArgumentException}
 * rather than silently ignoring it: applying it as an extra filter on the
 * join output is deliberately out of scope this week.
 */
public final class Planner {

    private static final Logger LOGGER = LoggerFactory.getLogger(Planner.class);

    /**
     * One table's pinned inputs. Since Exercise 7 the planner does not read
     * the catalog: it is handed the schema and the partition list of the
     * version the statement pinned, so nothing can change underneath a
     * running statement.
     */
    public record TableInput(String tableName, List<ColumnSpec> columns,
            List<Catalog.PartitionEntry> partitions, Path dataPath) {

        public TableInput {
            Objects.requireNonNull(tableName, "tableName");
            columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
            partitions = List.copyOf(Objects.requireNonNull(partitions, "partitions"));
            Objects.requireNonNull(dataPath, "dataPath");
        }
    }

    private final List<TableInput> tables;

    /** @param tables the pinned tables in {@code FROM}-list order */
    public Planner(List<TableInput> tables) {
        Objects.requireNonNull(tables, "tables");
        if (tables.isEmpty())
            throw new IllegalArgumentException("a plan needs at least one table");
        this.tables = List.copyOf(tables);
    }

    /**
     * Plans the statement's tables, filters and joins.
     *
     * @param filters the filters of every table, in statement order
     * @param joins   the join predicates, in statement order
     * @throws IllegalArgumentException for an unknown or ambiguous column, a
     *         constant whose type does not match, an unconnected table, or a
     *         table connected by more than one predicate
     */
    public Plan plan(List<Selection> filters, List<JoinCondition> joins) {
        long start = System.nanoTime();
        try {
            ColumnResolver resolver = new ColumnResolver(
                    tables.stream().map(TableInput::tableName).toList(),
                    tables.stream().map(TableInput::columns).toList());

            List<List<BoundFilter>> filtersByTable = bindFilters(resolver, filters);
            List<Operator> pipelines = new ArrayList<>(tables.size());
            ScanStats total = new ScanStats(0, 0, 0);
            for (int i = 0; i < tables.size(); i++) {
                TablePlan tablePlan = planTable(tables.get(i), filtersByTable.get(i));
                pipelines.add(tablePlan.root());
                total = total.plus(tablePlan.stats());
            }

            Operator joined = pipelines.get(0);
            for (int i = 1; i < pipelines.size(); i++) {
                JoinKey key = connectingKey(resolver, joins, i);
                joined = new HashJoinOperator(joined, pipelines.get(i), key.buildKey(), key.probeKey());
            }

            LOGGER.debug(
                    "tables={} joins={} partitionsTotal={} partitionsRead={} partitionsPruned={} durationMs={}",
                    tables.size(), joins.size(), total.partitionsTotal(), total.partitionsRead(),
                    total.partitionsPruned(), LogFormat.elapsedMs(start));
            return new Plan(joined, total);
        } catch (RuntimeException e) {
            LOGGER.debug("tables={} error={} durationMs={}",
                    tables.size(), e.getClass().getSimpleName(), LogFormat.elapsedMs(start));
            throw e;
        }
    }

    private List<List<BoundFilter>> bindFilters(ColumnResolver resolver, List<Selection> filters) {
        List<List<BoundFilter>> byTable = new ArrayList<>();
        for (int i = 0; i < tables.size(); i++) {
            byTable.add(new ArrayList<>());
        }
        for (Selection filter : filters) {
            ResolvedColumn column = resolver.resolve(filter.columnName());
            column.column().requireConstant(filter.constant());
            byTable.get(column.tableIndex()).add(new BoundFilter(filter, column));
        }
        return byTable;
    }

    /**
     * One table's pipeline: pruning first, then the filters below the joins.
     * A partition is pruned as soon as any filter on this table proves it
     * free, because all filters are ANDed together.
     */
    private static TablePlan planTable(TableInput table, List<BoundFilter> filters) {
        List<Catalog.PartitionEntry> survivors = new ArrayList<>();
        int pruned = 0;
        for (Catalog.PartitionEntry partition : table.partitions()) {
            boolean provablyFree = false;
            for (BoundFilter filter : filters) {
                String columnName = filter.column().column().name();
                Catalog.ColumnSummary summary = partition.summary(columnName);
                // Never prune on missing metadata: a partition that is never
                // read can never be recovered by the filter below.
                if (summary == null || summary.min() == null || summary.max() == null)
                    throw new IllegalStateException("missing min/max summary for column "
                            + columnName + " in partition " + partition.index()
                            + " of table " + table.tableName()
                            + "; refusing to prune on incomplete catalog metadata");
                Pruning.Decision decision = Pruning.decide(filter.selection().comparison(),
                        filter.selection().constant(), summary.min(), summary.max(),
                        filter.column().column().type());
                LOGGER.debug(
                        "table={} column={} comparison={} const={} partition={} min={} max={} decision={}",
                        LogFormat.sanitize(table.tableName()), LogFormat.sanitize(columnName),
                        filter.selection().comparison(), LogFormat.sanitize(filter.selection().constant()),
                        partition.index(), LogFormat.sanitize(summary.min()),
                        LogFormat.sanitize(summary.max()),
                        decision == Pruning.Decision.PROVABLY_FREE ? "PRUNED" : "READ");
                if (decision == Pruning.Decision.PROVABLY_FREE) {
                    provablyFree = true;
                    break;
                }
            }
            if (provablyFree) pruned++;
            else survivors.add(partition);
        }

        Operator root = new ScanOperator(table.dataPath(), table.columns(), survivors);
        for (BoundFilter filter : filters) {
            root = new FilterOperator(root, filter.column().tableColumnIndex(),
                    filter.column().column(), filter.selection().comparison(),
                    filter.selection().constant());
        }

        ScanStats stats = new ScanStats(table.partitions().size(), survivors.size(), pruned);
        LOGGER.debug("table={} filters={} partitionsTotal={} partitionsRead={} partitionsPruned={}",
                LogFormat.sanitize(table.tableName()), filters.size(), stats.partitionsTotal(),
                stats.partitionsRead(), stats.partitionsPruned());
        return new TablePlan(root, stats);
    }

    /**
     * The one join predicate that connects {@code newTable} to a table before
     * it. Its build side key indexes the intermediate result, which is the
     * concatenation of tables {@code 0..newTable-1}; its probe key indexes the
     * new table's own row.
     */
    private JoinKey connectingKey(ColumnResolver resolver, List<JoinCondition> joins, int newTable) {
        JoinKey found = null;
        String newTableName = tables.get(newTable).tableName();
        for (JoinCondition join : joins) {
            ResolvedColumn left = resolver.resolve(join.leftColumn());
            ResolvedColumn right = resolver.resolve(join.rightColumn());

            ResolvedColumn build;
            ResolvedColumn probe;
            if (left.tableIndex() == newTable && right.tableIndex() < newTable) {
                probe = left;
                build = right;
            } else if (right.tableIndex() == newTable && left.tableIndex() < newTable) {
                probe = right;
                build = left;
            } else {
                // Either both sides are already joined (used by an earlier
                // step) or the other side comes later; wait for that table.
                continue;
            }

            if (found != null)
                throw new IllegalArgumentException("table " + newTableName
                        + " is connected by more than one join predicate; only one "
                        + "connecting predicate per new table is supported");
            found = new JoinKey(build.rowIndex(), probe.tableColumnIndex());
        }
        if (found == null)
            throw new IllegalArgumentException("no join predicate connects table " + newTableName
                    + " to the tables before it in the FROM list; reorder the FROM list");
        return found;
    }


    private record BoundFilter(Selection selection, ResolvedColumn column) {
    }

    private record TablePlan(Operator root, ScanStats stats) {
    }

    private record JoinKey(int buildKey, int probeKey) {
    }
}
