package dk.itu.swandb.plan;

/**
 * Passed to a {@link HashJoinOperator} at plan time so its {@code close()}
 * line can carry the estimate next to the actuals, the exercised form of
 * Exercise 11 section 5:
 *
 * <pre>
 * join=1 tables=products&lt;orders estRows=4 rowsBuild=2 rowsProbe=4 rowsOut=4
 * </pre>
 *
 * <p>{@code tables} names both inputs, in the order they join, so a reader of
 * the log can tell a reordered plan from the {@code FROM} list alone.
 *
 * @param number      the join's position in the left-deep chain, 1-based
 * @param tables      the inputs' table names, in build/probe order
 * @param estRows     the optimizer's estimate of this join's output rows
 * @param crossProduct whether no join predicate connected the two inputs
 */
public record JoinEstimate(int number, String tables, long estRows, boolean crossProduct) {

    public JoinEstimate {
        if (number < 1)
            throw new IllegalArgumentException("a join number starts at 1: " + number);
        if (tables == null || tables.isEmpty())
            throw new IllegalArgumentException("a join estimate must name its inputs");
        if (estRows < 0)
            throw new IllegalArgumentException("an estimate must be >= 0: " + estRows);
    }
}
