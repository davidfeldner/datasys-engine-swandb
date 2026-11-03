package dk.itu.swandb;

import dk.itu.swandb.enums.ColumnType;
import dk.itu.swandb.enums.Comparison;

/**
 * Decides whether a partition can be skipped based on its min/max range
 * and a single-column comparison predicate. Used by the planner to avoid
 * touching partitions whose values are entirely outside the predicate
 * range.
 *
 * <p>Since Exercise 7 it is also the one place that answers the opposite
 * question for {@code DELETE}: {@link #canPrune} says a partition is
 * <em>provably</em> free of matches, {@link #mayContainMatch} says the same
 * thing positively, and {@link #mustRewrite} turns the model into the
 * rewrite set — exactly the complement of the pruning decision.
 */
public final class Pruning {

    /** What one partition's summary means for one partition. */
    public enum Decision {
        /** The summary proves no row can match: skip it (SELECT) or carry it (DELETE). */
        PROVABLY_FREE,
        /** A row may match: read it (SELECT) or rewrite it (DELETE). */
        MAY_MATCH
    }

    private Pruning() {
    }

    /**
     * The pruning decision itself, in the model's own vocabulary. Pruning a
     * partition, carrying it over, and adding it to a DELETE's rewrite set
     * are all read out of this one answer, so SELECT and DELETE can never
     * disagree about a partition.
     *
     * @throws IllegalArgumentException if {@code min} or {@code max} is
     *         {@code null}; see {@link #canPrune}.
     */
    public static Decision decide(Comparison comparison, Object constant,
                                  Object min, Object max, ColumnType type) {
        return canPrune(comparison, constant, min, max, type) ? Decision.PROVABLY_FREE : Decision.MAY_MATCH;
    }

    /**
     * The DELETE side of the same decision: whether a partition can only be
     * carried over by reference because no row in it can match.
     */
    public static boolean mustRewrite(Comparison comparison, Object constant,
                                       Object min, Object max, ColumnType type) {
        return decide(comparison, constant, min, max, type) == Decision.MAY_MATCH;
    }

    /**
     * One row against the predicate, with the same semantics the planner's
     * {@link dk.itu.swandb.plan.FilterOperator} applies, so a DELETE keeps
     * exactly the rows a {@code SELECT} with the same predicate would
     * return.
     */
    public static boolean matches(Comparison comparison, Object constant,
                                  Object value, ColumnType type) {
        int cmp = MinMax.compare(type, constant, value);
        return switch (comparison) {
            case EQUALS -> cmp == 0;
            case LESS_THAN -> cmp > 0;
            case GREATER_THAN -> cmp < 0;
        };
    }

    /**
     * @return {@code true} if the partition may be safely skipped,
     *         {@code false} if it must be read.
     * @throws IllegalArgumentException if {@code min} or {@code max} is
     *         {@code null}. A partition without a min/max summary cannot be
     *         proven to be outside the predicate range, so pruning it would
     *         silently drop rows; the caller must treat the missing summary as
     *         an error instead.
     */
    public static boolean canPrune(Comparison comparison, Object constant,
                                   Object min, Object max, ColumnType type) {
        if (min == null || max == null)
            throw new IllegalArgumentException(
                    "min and max must not be null: a partition without a min/max summary cannot be pruned safely");
        return switch (comparison) {
            // constant strictly below min OR strictly above max -> no match possible
            case EQUALS -> MinMax.compare(type, constant, min) < 0
                        || MinMax.compare(type, constant, max) > 0;
            // predicate: value < constant. Prune when no value is below constant, i.e. min >= constant.
            case LESS_THAN -> MinMax.compare(type, min, constant) >= 0;
            // predicate: value > constant. Prune when no value is above constant, i.e. max <= constant.
            case GREATER_THAN -> MinMax.compare(type, max, constant) <= 0;
        };
    }
}
