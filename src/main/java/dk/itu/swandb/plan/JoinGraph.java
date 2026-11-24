package dk.itu.swandb.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The query's join predicates as a graph over the {@code FROM}-list tables:
 * one node per table and one edge per equi-join predicate. The optimizer
 * walks this graph, so keeping it separate from the operator tree is what
 * makes the ordering decision testable without any data files.
 *
 * <p>Tables are named by their {@code FROM}-list index, which is also the
 * index into the planner's schema list.
 */
public final class JoinGraph {

    /**
     * One equi-join predicate. Both sides are key columns, so the column's
     * NDV is what the estimator needs; the index is its position inside the
     * table's own row and the name is only for the log.
     */
    public record Edge(int leftTable, int leftColumn, String leftColumnName,
                       int rightTable, int rightColumn, String rightColumnName) {

        public Edge {
            if (leftTable == rightTable)
                throw new IllegalArgumentException("a join edge needs two different tables: " + leftTable);
            if (leftColumn < 0 || rightColumn < 0)
                throw new IllegalArgumentException("join key columns must be >= 0");
            Objects.requireNonNull(leftColumnName, "leftColumnName");
            Objects.requireNonNull(rightColumnName, "rightColumnName");
        }

        /** The other end of the edge, seen from {@code table}. */
        public int other(int table) {
            if (table == leftTable) return rightTable;
            if (table == rightTable) return leftTable;
            throw new IllegalArgumentException("table " + table + " is not an end of this edge");
        }

        /** The key column that {@code table} contributes to this edge. */
        public int keyColumn(int table) {
            if (table == leftTable) return leftColumn;
            if (table == rightTable) return rightColumn;
            throw new IllegalArgumentException("table " + table + " is not an end of this edge");
        }

        /** The key column's name that {@code table} contributes. */
        public String keyName(int table) {
            if (table == leftTable) return leftColumnName;
            if (table == rightTable) return rightColumnName;
            throw new IllegalArgumentException("table " + table + " is not an end of this edge");
        }
    }

    private final int tableCount;
    private final List<Edge> edges;

    /**
     * @param tableCount how many tables the {@code FROM} list names
     * @param edges      the join predicates, in statement order
     */
    public JoinGraph(int tableCount, List<Edge> edges) {
        if (tableCount < 1)
            throw new IllegalArgumentException("a join graph needs at least one table: " + tableCount);
        if (edges == null)
            throw new IllegalArgumentException("edges must not be null");
        for (Edge edge : edges) {
            if (edge == null)
                throw new IllegalArgumentException("edges must not contain null");
            if (edge.leftTable() >= tableCount || edge.rightTable() >= tableCount)
                throw new IllegalArgumentException("edge names a table outside the FROM list: " + edge);
        }
        this.tableCount = tableCount;
        this.edges = List.copyOf(edges);
    }

    public int tableCount() { return tableCount; }

    public List<Edge> edges() { return edges; }

    /** Every edge with exactly one end in {@code joinedTables}, in statement order. */
    public List<Edge> connecting(List<Integer> joinedTables) {
        List<Edge> out = new ArrayList<>();
        for (Edge edge : edges) {
            if (joinedTables.contains(edge.leftTable()) != joinedTables.contains(edge.rightTable())) out.add(edge);
        }
        return out;
    }

    /**
     * Whether every table lies in one connected component: a breadth-first
     * walk from table 0 that must reach every table.
     */
    public boolean connected() {
        List<Integer> reached = new ArrayList<>(List.of(0));
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Edge edge : edges) {
                if (reached.contains(edge.leftTable()) && !reached.contains(edge.rightTable())) {
                    reached.add(edge.rightTable());
                    grew = true;
                } else if (reached.contains(edge.rightTable()) && !reached.contains(edge.leftTable())) {
                    reached.add(edge.leftTable());
                    grew = true;
                }
            }
        }
        return reached.size() == tableCount;
    }

    /**
     * The tables with no edge at all: the reason a FROM list is not
     * connected when one table stands apart from every other.
     */
    public List<Integer> isolatedTables() {
        List<Integer> out = new ArrayList<>();
        for (int t = 0; t < tableCount; t++) {
            boolean touched = false;
            for (Edge edge : edges) {
                if (edge.leftTable() == t || edge.rightTable() == t) {
                    touched = true;
                    break;
                }
            }
            if (!touched) out.add(t);
        }
        return out;
    }
}
