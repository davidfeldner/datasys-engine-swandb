package dk.itu.swandb;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Counts the distinct values of one column, the NDV statistic
 * {@code COPY} collects next to min/max (Exercise 11, section 2) and the
 * roll-forward path later recomputes for a partition whose catalog entry has
 * none.
 *
 * <p>Exactly, with a {@link HashSet}: at course scale (10<sup>4</sup> rows per
 * partition) the set is small and the count is right. At a billion rows the
 * same answer would come from a mergeable sketch — HyperLogLog or KMV — that
 * holds megabytes instead of the whole value set; see
 * {@code docs/optimizer-design.md}.
 *
 * <p>{@code DOUBLE} values are counted the way the join compares them, by
 * {@link Double#equals}: {@code equals} keeps {@code 0.0} and {@code -0.0}
 * distinct and treats every NaN as equal, which is precisely the semantics
 * the hash join's {@code HashMap} uses, because a join compares keys with
 * {@code equals} and never with {@code <}.
 */
public final class DistinctValues {

    private DistinctValues() {
    }

    /**
     * The number of distinct values in {@code values}. A {@code null} value
     * cannot occur ({@code COPY} rejects empty fields), so it is rejected
     * rather than silently counted as a value.
     */
    public static long count(List<Object> values) {
        if (values == null)
            throw new IllegalArgumentException("values must not be null");
        Set<Object> distinct = new HashSet<>(Math.max(16, values.size() * 2));
        for (Object value : values) {
            if (value == null)
                throw new IllegalArgumentException("values must not contain null");
            distinct.add(value);
        }
        return distinct.size();
    }
}
