package dk.itu.swandb.plan;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.swandb.Catalog;
import dk.itu.swandb.ColumnSpec;
import dk.itu.swandb.Estimator;
import dk.itu.swandb.JoinMode;
import dk.itu.swandb.LogFormat;
import dk.itu.swandb.Pruning;
import dk.itu.swandb.ScanStats;
import dk.itu.swandb.Valuation;
import dk.itu.swandb.plan.ColumnResolver.ResolvedColumn;
import dk.itu.swandb.plan.JoinOptimizer.TableEstimate;

/**
 * Turns pinned table versions plus the statement's filters and join
 * predicates into an operator tree. This is where partition pruning happens,
 * and since Exercise 11 where the join order is chosen and estimated.
 *
 * <p>One pipeline is built per table, exactly as in Part 1: a
 * {@link ScanOperator} over the partitions that survive pruning, wrapped in
 * one {@link FilterOperator} per filter bound to that table. Pruning still
 * works per table because each filter binds to exactly one table.
 *
 * <p>The pipelines are then joined left to right. In {@link JoinMode#SYNTAX}
 * that order is the {@code FROM} list, the Exercise 10 plan, unchanged. In
 * {@link JoinMode#OPTIMIZED} the {@link JoinOptimizer} ranks the orders by
 * their estimated intermediate sizes and the planner builds the winner. Each
 * join's smaller estimated input becomes the build side, so the hash table
 * holds the smaller relation.
 *
 * <p>Output rows are always the {@code FROM} list's concatenation: if the
 * chosen join order differs, one {@link ColumnReorderOperator} at the top
 * puts the columns back, so {@code SELECT *} returns the same columns in the
 * same order under both modes.
 *
 * <p><b>More than one connecting predicate.</b> The supported plans are
 * left-deep chains in which each added table is connected by exactly one
 * predicate; a second predicate for the same addition is rejected with an
 * {@link IllegalArgumentException} rather than silently ignored. A
 * {@code FROM} list that no predicate chain connects is rejected too, because
 * executing it would mean a cross product.
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
    private final JoinMode mode;

    /**
     * @param tables the pinned tables in {@code FROM}-list order
     */
    public Planner(List<TableInput> tables) {
        this(tables, JoinMode.OPTIMIZED);
    }

    /**
     * @param tables the pinned tables in {@code FROM}-list order
     * @param mode   the join order to plan: optimized or the FROM list itself
     */
    public Planner(List<TableInput> tables, JoinMode mode) {
        Objects.requireNonNull(tables, "tables");
        if (tables.isEmpty())
            throw new IllegalArgumentException("a plan needs at least one table");
        this.tables = List.copyOf(tables);
        this.mode = Objects.requireNonNull(mode, "mode");
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
            List<List<Catalog.PartitionEntry>> survivorsByTable = new ArrayList<>(tables.size());
            ScanStats total = new ScanStats(0, 0, 0);
            for (int i = 0; i < tables.size(); i++) {
                TablePlan tablePlan = planTable(tables.get(i), filtersByTable.get(i));
                pipelines.add(tablePlan.root());
                survivorsByTable.add(tablePlan.survivors());
                total = total.plus(tablePlan.stats());
            }

            JoinGraph graph = joinGraph(resolver, joins);
            // The shape check comes first: a table connected twice is what
            // the user most likely did wrong, and saying so beats reporting
            // whichever other table is then left without a path to the rest.
            checkOnePredicatePerTable(graph);
            if (!graph.connected())
                throw notConnected(graph);
            List<TableEstimate> estimates = new ArrayList<>(tables.size());
            for (int i = 0; i < tables.size(); i++) {
                estimates.add(estimate(tables.get(i), survivorsByTable.get(i), filtersByTable.get(i)));
            }

            Joined joined = mode == JoinMode.SYNTAX
                    ? joinInFromOrder(pipelines, graph, estimates)
                    : joinOptimized(pipelines, graph, estimates);
            Operator root = ColumnOrder.restore(joined.root(), joined.rowOrder(), tables);
            String joinOrder = root == joined.root() ? joined.orderLine() : joined.orderLine() + " reordered=true";

            LOGGER.debug(
                    "tables={} joins={} partitionsTotal={} partitionsRead={} partitionsPruned={} durationMs={}",
                    tables.size(), joins.size(), total.partitionsTotal(), total.partitionsRead(),
                    total.partitionsPruned(), LogFormat.elapsedMs(start));
            return new Plan(root, total, joinOrder);
        } catch (RuntimeException e) {
            LOGGER.debug("tables={} error={} durationMs={}",
                    tables.size(), e.getClass().getSimpleName(), LogFormat.elapsedMs(start));
            throw e;
        }
    }

    // --- estimates -----------------------------------------------------

    /**
     * One table's plan-time estimate: its rows after the filters, and every
     * column's NDV merged from the surviving partitions. A partition with no
     * recorded NDV cannot be ranked against others and is read as NDV 0, the
     * value {@link Valuation#tableNdv} treats as "carries no information";
     * the engine's roll-forward makes sure that case does not reach here.
     */
    private static TableEstimate estimate(TableInput table, List<Catalog.PartitionEntry> survivors,
                                          List<BoundFilter> filters) {
        long rows = Valuation.tableRows(survivors.stream().map(Catalog.PartitionEntry::rowCount).toList());
        List<Long> ndvs = new ArrayList<>(table.columns().size());
        for (ColumnSpec column : table.columns()) {
            List<Long> perPartition = new ArrayList<>(survivors.size());
            for (Catalog.PartitionEntry partition : survivors) {
                Catalog.ColumnSummary summary = partition.summary(column.name());
                if (summary == null)
                    throw new IllegalStateException("missing summary for column " + column.name()
                            + " in partition " + partition.index() + " of table " + table.tableName());
                perPartition.add(summary.hasNdv() ? summary.ndv() : 0L);
            }
            ndvs.add(Valuation.tableNdv(perPartition));
        }

        // The filters are ANDed, so their estimates compose one after another
        // on the same relation.
        long filtered = Estimator.base(rows);
        long before = filtered;
        for (BoundFilter filter : filters) {
            filtered = Estimator.filter(filtered, ndvs.get(filter.column().tableColumnIndex()),
                    filter.selection().comparison());
            LOGGER.debug("table={} column={} comparison={} rowsIn={} ndv={} estRows={}",
                    LogFormat.sanitize(table.tableName()),
                    LogFormat.sanitize(filter.column().column().name()),
                    filter.selection().comparison(), before, ndvs.get(filter.column().tableColumnIndex()),
                    filtered);
            before = filtered;
        }

        LOGGER.debug("table={} rows={} estRows={}", LogFormat.sanitize(table.tableName()), rows, filtered);
        return new TableEstimate(table.tableName(), filtered, ndvs);
    }

    // --- join construction ---------------------------------------------

    /**
     * The tree the planner returns, the row's table order (the tables whose
     * columns make up each row, in row order), and the statement's
     * join-order log line.
     */
    private record Joined(Operator root, List<Integer> rowOrder, String orderLine) {
    }

    /**
     * Exercise 10's plan: fold the {@code FROM} list left to right, each new
     * table probing the result so far. Estimates still size each join and
     * decide which side builds, but the order never changes.
     */
    private Joined joinInFromOrder(List<Operator> pipelines, JoinGraph graph,
                                   List<TableEstimate> estimates) {
        Operator joined = pipelines.get(0);
        List<Integer> joinedTables = new ArrayList<>(List.of(0));
        long joinedRows = estimates.get(0).rows();
        List<Long> joinedNdvs = new ArrayList<>(estimates.get(0).columnNdvs());
        List<Long> fromRows = new ArrayList<>(List.of(joinedRows));
        List<Integer> layout = new ArrayList<>(List.of(0));
        for (int i = 1; i < pipelines.size(); i++) {
            int newTable = i;
            List<JoinGraph.Edge> connecting = graph.connecting(joinedTables).stream()
                    .filter(edge -> edge.leftTable() == newTable || edge.rightTable() == newTable)
                    .toList();
            if (connecting.isEmpty())
                throw noConnectingPredicate(newTable);
            if (connecting.size() > 1)
                throw severalConnectingPredicates(newTable);
            JoinGraph.Edge edge = connecting.get(0);
            long rows = Estimator.join(joinedRows, estimates.get(i).rows(),
                    joinedNdvs.get(edge.keyColumn(edge.other(i))), estimates.get(i).ndv(edge.keyColumn(i)));
            JoinStep step = buildJoin(joined, layout, i, pipelines, edge, joinedRows,
                    estimates.get(i).rows(), rows, i + 1);
            joined = step.operator();
            layout = step.rowLayout();
            fromRows.add(rows);
            joinedRows = rows;
            joinedNdvs = concatenate(joinedNdvs, estimates.get(i).columnNdvs());
            joinedTables.add(i);
        }
        logEstimates("syntax", fromRows);
        return new Joined(joined, layout, orderLine("syntax", List.of(), fromRows));
    }

    /** The optimized plan: the greedy order, logged join by join. */
    private Joined joinOptimized(List<Operator> pipelines, JoinGraph graph,
                                 List<TableEstimate> estimates) {
        JoinOptimizer.Decision decision = JoinOptimizer.order(estimates, graph);

        Operator joined = pipelines.get(decision.startTable());
        List<Integer> joinedTables = new ArrayList<>(List.of(decision.startTable()));
        List<Integer> layout = new ArrayList<>(List.of(decision.startTable()));
        long joinedRows = estimates.get(decision.startTable()).rows();
        List<Long> rowsAlong = new ArrayList<>();
        List<String> order = new ArrayList<>(List.of(tables.get(decision.startTable()).tableName()));

        for (int step = 0; step < decision.steps().size(); step++) {
            JoinOptimizer.Step s = decision.steps().get(step);
            int added = s.addedTable();
            JoinGraph.Edge edge = s.crossProduct() ? null : edgeToJoined(graph, joinedTables, added);
            JoinStep built = buildJoin(joined, layout, added, pipelines, edge, joinedRows,
                    estimates.get(added).rows(), s.estRows(), step + 1);
            joined = built.operator();
            layout = built.rowLayout();
            joinedRows = s.estRows();
            joinedTables.add(added);
            rowsAlong.add(joinedRows);
            order.add(tables.get(added).tableName());
        }

        logCandidates(decision.candidates());
        logEstimates("optimized", rowsAlong);
        LOGGER.debug("joinOrder={} mode=optimized rows={}", LogFormat.sanitize(String.join(",", order)),
                rowsAlong.isEmpty() ? estimates.get(decision.startTable()).rows() : rowsAlong.get(rowsAlong.size() - 1));
        return new Joined(joined, layout, orderLine("optimized", order, rowsAlong));
    }

    /**
     * One hash join, with its build side chosen as the smaller estimated
     * input. The inside (already joined) input is a plain table only for the
     * first join; afterwards its row is the intermediate result, whose
     * columns sit at the plan positions {@code positionOfTable} gives.
     */
    /**
     * One hash join over the intermediate result built so far.
     *
     * <p>The join's output row is the build row followed by the probe row, so
     * the caller learns the new row's table layout from this method's return:
     * see {@link JoinStep}.
     */
    private JoinStep buildJoin(Operator joined, List<Integer> joinedLayout, int added,
                               List<Operator> pipelines, JoinGraph.Edge edge,
                               long insideRows, long addedRows, long estRows, int number) {
        String insideNames = insideNames(joinedLayout);
        String addedName = tables.get(added).tableName();
        if (edge == null) {
            // A cross product keeps the intermediate result's layout: it builds.
            return new JoinStep(
                    new HashJoinOperator(joined, pipelines.get(added), 0, 0,
                            new JoinEstimate(number, insideNames + " x " + addedName, estRows, true)),
                    appendTable(joinedLayout, added));
        }

        int insideTable = edge.other(added);
        int insideKey = keyPosition(insideTable, edge.keyColumn(insideTable), joinedLayout);
        int addedKey = edge.keyColumn(added);
        if (insideRows <= addedRows) {
            // The intermediate result builds, so it stays first in the row.
            return new JoinStep(
                    new HashJoinOperator(joined, pipelines.get(added), insideKey, addedKey,
                            new JoinEstimate(number, insideNames + "<" + addedName, estRows, false)),
                    appendTable(joinedLayout, added));
        }
        // The added table builds, so its columns come first in the row.
        return new JoinStep(
                new HashJoinOperator(pipelines.get(added), joined, addedKey, insideKey,
                        new JoinEstimate(number, addedName + "<" + insideNames, estRows, false)),
                prependTable(added, joinedLayout));
    }

    /**
     * The intermediate row's table names, in row order, {@code >}-joined so
     * the log line stays free of the comma that separates its fields.
     */
    private String insideNames(List<Integer> layout) {
        List<String> names = new ArrayList<>(layout.size());
        for (int table : layout) names.add(tables.get(table).tableName());
        return String.join(">", names);
    }

    /**
     * Where {@code table}'s column {@code column} sits in a row laid out as
     * {@code layout}: the sum of the widths of the tables before it.
     */
    private int keyPosition(int table, int column, List<Integer> layout) {
        int position = 0;
        for (int laidOut : layout) {
            if (laidOut == table) return position + column;
            position += tables.get(laidOut).columns().size();
        }
        throw new IllegalArgumentException("table " + tables.get(table).tableName()
                + " is not part of the intermediate result");
    }

    /** A built join and the table layout of its output row. */
    private record JoinStep(Operator operator, List<Integer> rowLayout) {
    }

    private static List<Integer> appendTable(List<Integer> layout, int table) {
        List<Integer> out = new ArrayList<>(layout.size() + 1);
        out.addAll(layout);
        out.add(table);
        return out;
    }

    /**
     * The predicate that connects {@code table} to the tables already joined:
     * an edge with one end in {@code joinedTables} and the other at
     * {@code table}. It is the edge the optimizer ranked that step by.
     */
    private static JoinGraph.Edge edgeToJoined(JoinGraph graph, List<Integer> joinedTables, int table) {
        for (JoinGraph.Edge edge : graph.edges()) {
            if (edge.leftTable() != table && edge.rightTable() != table) continue;
            if (joinedTables.contains(edge.other(table))) return edge;
        }
        throw new IllegalStateException("no join predicate connects table " + table
                + " to the tables already joined");
    }

    private static List<Integer> prependTable(int table, List<Integer> layout) {
        List<Integer> out = new ArrayList<>(layout.size() + 1);
        out.add(table);
        out.addAll(layout);
        return out;
    }

    private static List<Long> concatenate(List<Long> left, List<Long> right) {
        List<Long> out = new ArrayList<>(left.size() + right.size());
        out.addAll(left);
        out.addAll(right);
        return out;
    }

    // --- logging -------------------------------------------------------

    /** The statement's join-order line, the {@link Plan}'s record of the plan chosen. */
    private String orderLine(String mode, List<String> order, List<Long> rowsAlong) {
        StringBuilder line = new StringBuilder("joinOrder=");
        if (order.isEmpty()) {
            for (TableInput table : tables) line.append(table.tableName()).append(',');
            line.setLength(line.length() - 1);
        } else {
            line.append(String.join(",", order));
        }
        line.append(" mode=").append(mode);
        for (int i = 0; i < rowsAlong.size(); i++) {
            line.append(" estRows").append(i + 1).append('=').append(rowsAlong.get(i));
        }
        return line.toString();
    }

    /** One line per combination the greedy search checked, so the choice can be audited. */
    private static void logCandidates(List<JoinOptimizer.Candidate> candidates) {
        for (JoinOptimizer.Candidate candidate : candidates) {
            LOGGER.debug("mode=optimized step={} considered={} joined={} estRows={} crossProduct={}",
                    candidate.step(), LogFormat.sanitize(candidate.table()),
                    LogFormat.sanitize(candidate.tables()), candidate.estRows(), candidate.crossProduct());
        }
    }

    /** One line per join's estimate, in the order the joins execute. */
    private static void logEstimates(String mode, List<Long> rowsAlong) {
        for (int i = 0; i < rowsAlong.size(); i++) {
            LOGGER.debug("mode={} join={} estRows={}", mode, i + 1, rowsAlong.get(i));
        }
    }

    // --- binding -------------------------------------------------------

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

    /** The join predicates as a graph, resolving each once. */
    private JoinGraph joinGraph(ColumnResolver resolver, List<JoinCondition> joins) {
        List<JoinGraph.Edge> edges = new ArrayList<>(joins.size());
        for (JoinCondition join : joins) {
            ResolvedColumn left = resolver.resolve(join.leftColumn());
            ResolvedColumn right = resolver.resolve(join.rightColumn());
            if (left.tableIndex() == right.tableIndex())
                throw new IllegalArgumentException("join sides must be in different tables: "
                        + left.column().name() + " and " + right.column().name()
                        + " both belong to table " + left.tableName());
            edges.add(new JoinGraph.Edge(left.tableIndex(), left.tableColumnIndex(),
                    left.column().name(), right.tableIndex(), right.tableColumnIndex(),
                    right.column().name()));
        }
        return new JoinGraph(tables.size(), edges);
    }

    /** Week 10's rejection of a table no predicate connects to the tables before it. */
    private IllegalArgumentException noConnectingPredicate(int table) {
        return new IllegalArgumentException("no join predicate connects table "
                + tables.get(table).tableName() + " to the tables before it in the FROM list; "
                + "reorder the FROM list");
    }

    /**
     * The supported shape is a chain in which each table is connected by
     * exactly one predicate; a second one is rejected rather than silently
     * ignored. Checked in both modes, so the flag changes the order of a plan
     * and never whether a statement is accepted.
     */
    private IllegalArgumentException severalConnectingPredicates(int table) {
        return new IllegalArgumentException("table " + tables.get(table).tableName()
                + " is connected by more than one join predicate; only one "
                + "connecting predicate per new table is supported");
    }

    /**
     * Rejects a table reached from the rest of the FROM list by two or more
     * predicates, the case neither mode supports. Doing it before the search
     * means the optimizer only ever ranks plans it can build.
     */
    private void checkOnePredicatePerTable(JoinGraph graph) {
        // Breadth-first from table 0, one table per step: the supported plan
        // shape is a chain, and a table offered more than one predicate by
        // the tables joined so far cannot be added by a single key.
        List<Integer> reached = new ArrayList<>(List.of(0));
        while (reached.size() < tables.size()) {
            List<JoinGraph.Edge> connecting = graph.connecting(reached);
            int next = -1;
            for (JoinGraph.Edge edge : connecting) {
                int outside = reached.contains(edge.leftTable()) ? edge.rightTable() : edge.leftTable();
                if (next < 0 || outside < next) next = outside;
            }
            if (next < 0) return;
            int candidate = next;
            long predicates = connecting.stream()
                    .filter(edge -> edge.leftTable() == candidate || edge.rightTable() == candidate)
                    .count();
            if (predicates > 1) throw severalConnectingPredicates(candidate);
            reached.add(candidate);
        }
    }

    /** Week 10's rejection of a FROM list no predicate chain connects. */
    private IllegalArgumentException notConnected(JoinGraph graph) {
        List<String> isolated = new ArrayList<>();
        for (int table : graph.isolatedTables()) isolated.add(tables.get(table).tableName());
        if (!isolated.isEmpty())
            return new IllegalArgumentException("no join predicate connects table " + isolated.get(0)
                    + " to the other tables of the FROM list; reorder the FROM list or add its join predicate");
        return new IllegalArgumentException(
                "no join predicate connects the FROM list into one chain; reorder the FROM list");
    }

    // --- per-table pipeline --------------------------------------------

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
        return new TablePlan(root, stats, survivors);
    }

    private record BoundFilter(Selection selection, ResolvedColumn column) {
    }

    private record TablePlan(Operator root, ScanStats stats,
                             List<Catalog.PartitionEntry> survivors) {
    }
}
