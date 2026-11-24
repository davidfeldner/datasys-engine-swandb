package dk.itu.swandb.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dk.itu.swandb.Estimator;

/**
 * Greedy smallest-intermediate-first join ordering: the heuristic of lecture
 * 10 and {@code docs/optimizer-design.md}, applied to a {@link JoinGraph}.
 *
 * <p>The search is the exercise's three steps:
 *
 * <ol>
 *   <li>Every table's size after pruning and filtering is estimated by the
 *       caller and handed in; the chain starts with the join predicate whose
 *       estimated output is smallest.</li>
 *   <li>Then, one table at a time, the table connected to the tables joined
 *       so far whose estimated join output is smallest is added.</li>
 *   <li>A table no predicate connects is ranked last and reached only when
 *       no connected table is left at all: a cross product never beats a
 *       connected choice.</li>
 * </ol>
 *
 * <p>The result is a left-deep plan — a list of {@link Step}s, each adding
 * one table to the intermediate result — plus the estimate of every
 * intermediate result, and every combination the search checked, so the
 * planner can log why it chose what it chose.
 *
 * <p>Rows, not tables: a join can multiply, so "size" always means the
 * estimated row count of the whole intermediate result.
 *
 * <p>Determinism: candidates tie-break on the table's {@code FROM}-list
 * index, so the plan is the same on every run.
 */
public final class JoinOptimizer {

    /**
     * One table's plan-time figures: its estimated size after pruning and
     * filters, and the NDV of each of its columns, indexed like the table's
     * own row.
     */
    public record TableEstimate(String tableName, long rows, List<Long> columnNdvs) {

        public TableEstimate {
            Objects.requireNonNull(tableName, "tableName");
            if (rows < 0)
                throw new IllegalArgumentException("estimated rows must be >= 0: " + rows);
            columnNdvs = List.copyOf(Objects.requireNonNull(columnNdvs, "columnNdvs"));
            for (long ndv : columnNdvs) {
                if (ndv < 0)
                    throw new IllegalArgumentException("estimated NDV must be >= 0: " + ndv);
            }
        }

        /** This table's NDV for one of its columns. */
        public long ndv(int column) {
            return columnNdvs.get(column);
        }
    }

    /**
     * One combination the search checked, at the step it was checked at.
     *
     * @param tables  the tables already joined when the candidate was ranked
     * @param table   the table being considered
     * @param estRows the estimated rows of joining {@code table} to them
     * @param step    the step number, 1-based
     * @param crossProduct whether no predicate connected the candidate
     */
    public record Candidate(List<Integer> tables, int table, long estRows,
                            int step, boolean crossProduct) {

        public Candidate {
            tables = List.copyOf(tables);
            if (estRows < 0)
                throw new IllegalArgumentException("an estimated output must be >= 0: " + estRows);
            if (step < 1)
                throw new IllegalArgumentException("a step number starts at 1: " + step);
        }
    }

    /**
     * One join in the left-deep chain.
     *
     * @param joinedBefore the tables joined before it, in join order
     * @param addedTable   the table this step adds
     * @param insideTable  the end of the connecting predicate already joined
     * @param insideKey    that end's key column
     * @param insideName   that end's key column name
     * @param addedKey     the added table's key column
     * @param addedName    the added table's key column name
     * @param estRows      the estimated rows of this join's output
     * @param crossProduct whether this step had no connecting predicate
     */
    public record Step(List<Integer> joinedBefore, int addedTable,
                       int insideTable, int insideKey, String insideName,
                       int addedKey, String addedName,
                       long estRows, boolean crossProduct) {

        public Step {
            joinedBefore = List.copyOf(joinedBefore);
            if (joinedBefore.isEmpty())
                throw new IllegalArgumentException("a join step needs at least one joined table");
            for (int table : joinedBefore) {
                if (table < 0 || addedTable < 0)
                    throw new IllegalArgumentException("table indices must be >= 0");
                if (table == addedTable)
                    throw new IllegalArgumentException("table " + table + " is already joined");
            }
            if (estRows < 0)
                throw new IllegalArgumentException("an estimated output must be >= 0: " + estRows);
            if (!crossProduct) {
                if (!joinedBefore.contains(insideTable))
                    throw new IllegalArgumentException("the inside table " + insideTable + " is not joined yet");
                if (insideKey < 0 || addedKey < 0)
                    throw new IllegalArgumentException("key columns must be >= 0");
            }
        }
    }

    /**
     * The chosen left-deep plan.
     *
     * @param steps      the join steps, each adding one table to the previous
     *                   result; each carries its own estimated output
     * @param startTable the {@code FROM}-list index the chain starts from
     * @param candidates every combination the search checked
     */
    public record Decision(List<Step> steps, int startTable, List<Candidate> candidates) {

        public Decision {
            steps = List.copyOf(steps);
            candidates = List.copyOf(candidates);
        }

        /** The join order: the start table, then each step's table. */
        public List<Integer> order() {
            List<Integer> out = new ArrayList<>(steps.size() + 1);
            out.add(startTable);
            for (Step step : steps) out.add(step.addedTable());
            return out;
        }

        /** Each intermediate result's estimated rows, in execution order. */
        public List<Long> tableRows() {
            List<Long> out = new ArrayList<>(steps.size());
            for (Step step : steps) out.add(step.estRows());
            return out;
        }
    }

    private JoinOptimizer() {
    }

    /**
     * Picks the order. The caller has already rejected a FROM list that is
     * not connected, so every table is part of one component.
     *
     * @param estimates one per table, in {@code FROM}-list order
     * @param graph     the join predicates over the same tables
     * @throws IllegalArgumentException for a mismatched table count, an empty
     *         plan, or a graph that is not connected
     */
    public static Decision order(List<TableEstimate> estimates, JoinGraph graph) {
        Objects.requireNonNull(estimates, "estimates");
        Objects.requireNonNull(graph, "graph");
        if (estimates.size() != graph.tableCount())
            throw new IllegalArgumentException("expected " + graph.tableCount()
                    + " table estimates, got " + estimates.size());
        if (estimates.isEmpty())
            throw new IllegalArgumentException("a join plan needs at least one table");
        if (!graph.connected())
            throw new IllegalArgumentException("the join graph is not connected: a cross product would be needed");
        if (estimates.size() == 1)
            return new Decision(List.of(), 0, List.of());

        List<Candidate> candidates = new ArrayList<>();

        // Step 1: the start predicate whose estimated output is smallest.
        JoinGraph.Edge start = null;
        long startRows = 0;
        for (JoinGraph.Edge edge : graph.edges()) {
            // Both ends are bare tables here, so either end can be the
            // "already joined" one for the estimate.
            long rows = joinEstimate(estimates, null, edge, edge.leftTable());
            candidates.add(new Candidate(List.of(), edge.rightTable(), rows, 1, false));
            boolean better = start == null
                    || rows < startRows
                    || rows == startRows && earlier(edge, start);
            if (better) {
                start = edge;
                startRows = rows;
            }
        }

        int startTable = start.leftTable();
        List<Integer> joinedTables = new ArrayList<>(List.of(startTable, start.rightTable()));
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(List.of(startTable), start.rightTable(),
                startTable, start.keyColumn(startTable), start.keyName(startTable),
                start.keyColumn(start.rightTable()), start.keyName(start.rightTable()),
                startRows, false));

        // Step 2: add the smallest connected table until every table is in.
        while (joinedTables.size() < estimates.size()) {
            int step = steps.size() + 1;
            long lastRows = steps.get(steps.size() - 1).estRows();
            Candidate best = null;
            JoinGraph.Edge bestEdge = null;
            for (int t = 0; t < estimates.size(); t++) {
                if (joinedTables.contains(t)) continue;
                JoinGraph.Edge edge = connectingEdge(graph, joinedTables, t);
                boolean cross = edge == null;
                long rows = cross
                        ? Estimator.join(lastRows, estimates.get(t).rows(), 1, 1)
                        : joinEstimate(estimates, new Result(lastRows,
                                intermediateNdvs(estimates, joinedTables)), edge, t);
                Candidate candidate = new Candidate(joinedTables, t, rows, step, cross);
                candidates.add(candidate);
                if (isBetter(candidate, best)) {
                    best = candidate;
                    bestEdge = edge;
                }
            }
            JoinGraph.Edge edge = bestEdge;
            int added = best.table();
            List<Integer> before = new ArrayList<>(joinedTables);
            steps.add(edge == null
                    ? new Step(before, added, -1, -1, "", -1, "", best.estRows(), true)
                    : new Step(before, added,
                            edge.other(added), edge.keyColumn(edge.other(added)), edge.keyName(edge.other(added)),
                            edge.keyColumn(added), edge.keyName(added), best.estRows(), false));
            joinedTables.add(added);
        }
        return new Decision(steps, startTable, candidates);
    }

    /** An intermediate result: estimated rows and per-column NDVs in row order. */
    private record Result(long rows, List<Long> columnNdvs) {
    }

    /**
     * The estimated rows of adding {@code added} over {@code edge}, from the
     * result so far (or the bare tables when {@code soFar} is {@code null})
     * and the NDVs of the two key columns.
     */
    private static long joinEstimate(List<TableEstimate> estimates, Result soFar,
                                     JoinGraph.Edge edge, int added) {
        int inside = edge.other(added);
        long insideRows;
        List<Long> insideNdvs;
        if (soFar == null) {
            insideRows = estimates.get(inside).rows();
            insideNdvs = estimates.get(inside).columnNdvs();
        } else {
            insideRows = soFar.rows();
            insideNdvs = soFar.columnNdvs();
        }
        return Estimator.join(insideRows, estimates.get(added).rows(),
                insideNdvs.get(edge.keyColumn(inside)), estimates.get(added).ndv(edge.keyColumn(added)));
    }

    /** Ranks a candidate: smallest estimate first, then the earlier FROM-list table. */
    private static boolean isBetter(Candidate candidate, Candidate best) {
        if (best == null) return true;
        if (candidate.estRows() != best.estRows()) return candidate.estRows() < best.estRows();
        return candidate.table() < best.table();
    }

    /** Whether {@code edge} comes before {@code other} by FROM-list position. */
    private static boolean earlier(JoinGraph.Edge edge, JoinGraph.Edge other) {
        return first(edge) < first(other);
    }

    /** The FROM-list index of an edge's first end, by position not by predicate order. */
    private static int first(JoinGraph.Edge edge) {
        return Math.min(edge.leftTable(), edge.rightTable());
    }

    /**
     * The predicate that adds {@code table}: an edge with one end in
     * {@code joinedTables} and the other at {@code table}, or {@code null}
     * when no predicate connects them.
     */
    private static JoinGraph.Edge connectingEdge(JoinGraph graph, List<Integer> joinedTables, int table) {
        for (JoinGraph.Edge edge : graph.edges()) {
            if (edge.leftTable() != table && edge.rightTable() != table) continue;
            if (joinedTables.contains(edge.other(table))) return edge;
        }
        return null;
    }

    /**
     * The intermediate result's per-column NDVs, in row order: the columns of
     * the joined tables in {@code FROM}-list order. A base table's column
     * keeps its NDV, exactly the exercise's assumption that an intermediate
     * result's NDV for a column is the NDV of the base table that contributed
     * it. The inside key of the next predicate always belongs to a table that
     * is already joined, so this is the NDV the next estimate reads. A true
     * refinement would narrow a key's NDV to the smaller of the two sides'
     * NDVs once a join has matched it; the left-deep chain never reads a key
     * that way, so this simpler rule is exact for the plans we build.
     */
    private static List<Long> intermediateNdvs(List<TableEstimate> estimates, List<Integer> joinedTables) {
        List<Long> out = new ArrayList<>();
        for (int t = 0; t < estimates.size(); t++) {
            if (joinedTables.contains(t)) out.addAll(estimates.get(t).columnNdvs());
        }
        return out;
    }
}
