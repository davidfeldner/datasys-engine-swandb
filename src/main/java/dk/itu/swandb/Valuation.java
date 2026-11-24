package dk.itu.swandb;

import java.util.List;

/**
 * How one per-partition statistic becomes the single number the planner
 * reads: the merge rules of {@code docs/optimizer-design.md}.
 *
 * <p>Row counts merge by {@link #tableRows exact sum}. NDV does not: the
 * same value may appear in many partitions, so the maximum of the
 * per-partition NDVs is a lower bound on the table's NDV and their sum is an
 * upper bound. The engine merges by maximum ({@link #tableNdv}). Partitions
 * are small relative to the number of distinct values in them, so each
 * partition's NDV already approximates the table's; a uniform hash
 * distribution over the table makes the maximum the most likely partition to
 * have seen nearly every value, and the sum would overcount by up to the
 * partition count. The course baseline in lecture 10 uses the same rule.
 *
 * <p>Exact merge is the alternative — what a HyperLogLog sketch gives — and
 * is the switch to make at a billion rows; see the design document.
 */
public final class Valuation {

    private Valuation() {
    }

    /**
     * The row count of a table after pruning: the sum of the surviving
     * partitions' row counts.
     */
    public static long tableRows(List<Integer> partitionRows) {
        requireNonNull(partitionRows, "partition row counts");
        long total = 0;
        for (int rows : partitionRows) {
            if (rows < 0)
                throw new IllegalArgumentException("a partition's row count must be >= 0: " + rows);
            total += rows;
        }
        return total;
    }

    /**
     * The table's NDV for one column under the maximum rule. An empty table
     * has NDV 1 rather than 0: no row can equal anything, and 1 keeps every
     * downstream divisor positive, so the unknown case behaves exactly like
     * "one distinct value", which is what an empty table would support.
     *
     * @throws IllegalArgumentException if a partition records a negative NDV,
     *         which no writer produces
     */
    public static long tableNdv(List<Long> partitionNdvs) {
        requireNonNull(partitionNdvs, "partition NDVs");
        long max = 0;
        for (long ndv : partitionNdvs) {
            if (ndv < 0)
                throw new IllegalArgumentException("a partition's NDV must be >= 0: " + ndv);
            max = Math.max(max, ndv);
        }
        return Math.max(max, 1);
    }

    private static void requireNonNull(List<?> values, String what) {
        if (values == null) throw new IllegalArgumentException(what + " must not be null");
        for (Object value : values) {
            if (value == null) throw new IllegalArgumentException(what + " must not contain null");
        }
    }
}
