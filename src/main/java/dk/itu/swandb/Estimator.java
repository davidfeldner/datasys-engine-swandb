package dk.itu.swandb;

import dk.itu.swandb.enums.Comparison;

/**
 * The three size estimates the join optimizer ranks plans by, each a pure
 * function over catalog numbers so it is unit-testable without data files.
 * The formulas and their sources are stated in
 * {@code docs/optimizer-design.md}:
 *
 * <ul>
 *   <li>{@link #base base}: the row counts of the partitions that survive
 *       pruning, summed; no assumption, it is what the scan will read.</li>
 *   <li>{@link #filter filter}: an equality keeps {@code rows / ndv} rows,
 *       an inequality {@code rows / 3}. The {@code 1/3} is Selinger's magic
 *       constant for a comparison on a range column (Selinger et al.,
 *       "Access Path Selection in a Relational Database Management
 *       System", SIGMOD 1979).</li>
 *   <li>{@link #join join}: {@code |R| * |S| / max(ndv(R.a), ndv(S.b))},
 *       the equi-join estimate of lecture 10, which assumes the smaller
 *       column's values are a random subset of the larger's.</li>
 * </ul>
 *
 * <p>Every result is rounded to a whole row and floored at 1: an estimate of
 * zero would make the next join in the chain estimate zero as well and rank
 * every order alike.
 */
public final class Estimator {

    /** Selinger's selectivity of a comparison on a range column. */
    public static final double INEQUALITY_SELECTIVITY = 1.0 / 3;

    private Estimator() {
    }

    /**
     * The estimated rows of a base table whose partitions survive pruning.
     *
     * @throws IllegalArgumentException on a negative or {@code null} input
     */
    public static long base(Long rows) {
        if (rows == null || rows < 0)
            throw new IllegalArgumentException("base rows must be >= 0: " + rows);
        return Math.max(rows, 1);
    }

    /**
     * The estimated rows after {@code comparison} against a constant.
     *
     * <p>An equality with {@code ndv} 0 asks about a column no row has a
     * value for, which cannot happen for a non-empty table; it is read as
     * "every value matches", the same answer the {@code max(ndv, 1)} floor
     * gives for NDV 1.
     *
     * @param rows the estimated rows of the input
     * @param ndv  the number of distinct values of the filtered column
     * @throws IllegalArgumentException on a negative input or a {@code null}
     *         comparison
     */
    public static long filter(long rows, long ndv, Comparison comparison) {
        if (rows < 0)
            throw new IllegalArgumentException("filter rows must be >= 0: " + rows);
        if (ndv < 0)
            throw new IllegalArgumentException("filter NDV must be >= 0: " + ndv);
        if (comparison == null)
            throw new IllegalArgumentException("comparison must not be null");
        double output = comparison == Comparison.EQUALS
                ? (double) rows / Math.max(ndv, 1)
                : rows * INEQUALITY_SELECTIVITY;
        return rounded(output);
    }

    /**
     * The estimated rows of an equi-join on one key pair, from the inputs'
     * estimated sizes and the NDVs of the two key columns.
     *
     * <p>For a later join in a chain the key column may belong to an
     * intermediate result, whose NDV is taken to be the NDV of the base table
     * that contributed the column: see {@code JoinOptimizer} and
     * {@code docs/optimizer-design.md}. That assumption is the first place
     * the estimates can diverge from reality, which is why it is written
     * down.
     *
     * @throws IllegalArgumentException on a negative input
     */
    public static long join(long leftRows, long rightRows, long leftNdv, long rightNdv) {
        if (leftRows < 0 || rightRows < 0)
            throw new IllegalArgumentException("join inputs must be >= 0: "
                    + leftRows + " and " + rightRows);
        if (leftNdv < 0 || rightNdv < 0)
            throw new IllegalArgumentException("join NDVs must be >= 0: "
                    + leftNdv + " and " + rightNdv);
        double output = (double) leftRows * rightRows
                / Math.max(Math.max(leftNdv, rightNdv), 1);
        return rounded(output);
    }

    /** Round to the nearest whole row, never below 1. */
    private static long rounded(double value) {
        return Math.max(Math.round(value), 1);
    }
}
