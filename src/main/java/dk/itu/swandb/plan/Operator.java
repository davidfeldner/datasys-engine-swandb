package dk.itu.swandb.plan;

/**
 * The Volcano operator model of Exercise 4: a pull-based iterator that
 * produces one row per {@link #next()} call and {@code null} once exhausted.
 *
 * <p>Rows are {@code Object[]} in schema column order, exactly the shape
 * {@code StorageEngine.select} returned in week 2, so the refactor does not
 * change the engine's observable result type.
 */
public interface Operator {

    /** Prepare the operator for a fresh drain. */
    void open();

    /** One row in schema column order, or {@code null} when exhausted. */
    Object[] next();

    /** Release resources and report the operator's own counters. */
    void close();
}
