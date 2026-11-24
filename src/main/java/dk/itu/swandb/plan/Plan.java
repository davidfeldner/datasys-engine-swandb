package dk.itu.swandb.plan;

import dk.itu.swandb.ScanStats;

/**
 * A planned operator tree, the {@link ScanStats} the planner observed while
 * building it, and the join order it chose.
 *
 * <p>{@code joinOrder} is the statement's own record of the plan — which
 * tables were joined, in which mode, with each join's estimate — so an
 * integration test can assert that two modes really built different plans
 * without parsing the log. It is also what the executor logs.
 */
public record Plan(Operator root, ScanStats stats, String joinOrder) {

    public Plan {
        if (joinOrder == null) joinOrder = "";
    }

    /** A plan for a statement that joins nothing, carrying no order line. */
    public Plan(Operator root, ScanStats stats) {
        this(root, stats, "");
    }
}
