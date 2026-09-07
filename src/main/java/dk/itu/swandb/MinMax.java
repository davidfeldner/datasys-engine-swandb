package dk.itu.swandb;

import java.util.List;

/**
 * Computes a min/max pair over a list of values, using the comparison
 * semantics of the column type (numeric for LONG/DOUBLE, lexicographic
 * ASCII for STRING).
 */
public final class MinMax {

    private MinMax() {
    }

    /** Result of a min/max computation; immutable. */
    public record Result(Object min, Object max) {
    }

    /**
     * Compute the min and max of {@code values} for the given column type.
     * An empty input yields {@code null} for both fields.
     */
    public static Result compute(ColumnType type, List<Object> values) {
        if (values == null || values.isEmpty()) {
            return new Result(null, null);
        }
        Object min = values.get(0);
        Object max = values.get(0);
        for (int i = 1; i < values.size(); i++) {
            Object v = values.get(i);
            if (compare(type, v, min) < 0) min = v;
            if (compare(type, v, max) > 0) max = v;
        }
        return new Result(min, max);
    }

    /** Lexicographic/numeric comparison matching the column's data type. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static int compare(ColumnType type, Object a, Object b) {
        return switch (type) {
            case LONG -> Long.compare((Long) a, (Long) b);
            case DOUBLE -> Double.compare((Double) a, (Double) b);
            case STRING -> {
                Comparable aa = (Comparable) a;
                yield aa.compareTo(b);
            }
        };
    }
}
