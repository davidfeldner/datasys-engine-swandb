package dk.itu.swandb;

/**
 * Decides whether a partition can be skipped based on its min/max range
 * and a single-column comparison predicate. Used by the scan loop to
 * avoid touching partitions whose values are entirely outside the
 * predicate range.
 */
public final class Pruning {

    private Pruning() {
    }

    /**
     * @return {@code true} if the partition may be safely skipped,
     *         {@code false} if it must be read.
     */
    public static boolean canPrune(Comparison comparison, Object constant,
                                   Object min, Object max, ColumnType type) {
        if (min == null || max == null) {
            // Empty partition; reading it is safe but never yields rows.
            return true;
        }
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
