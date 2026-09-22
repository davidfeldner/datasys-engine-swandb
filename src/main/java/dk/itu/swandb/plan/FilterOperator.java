package dk.itu.swandb.plan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.swandb.ColumnSpec;
import dk.itu.swandb.MinMax;
import dk.itu.swandb.enums.Comparison;

/**
 * Pulls rows from its child and emits those that pass the predicate, using
 * the same comparison semantics as week 2 ({@link MinMax#compare}). It is a
 * plain row test: all partition-level thinking happened in the planner.
 *
 * <p>{@link #close()} logs how many rows came in and how many went out, the
 * filter's own contribution to the pipeline's observability.
 */
public final class FilterOperator implements Operator {

    private static final Logger LOGGER = LoggerFactory.getLogger(FilterOperator.class);

    private final Operator child;
    private final int columnIndex;
    private final ColumnSpec column;
    private final Comparison comparison;
    private final Object constant;

    private long rowsIn;
    private long rowsOut;

    public FilterOperator(Operator child, int columnIndex, ColumnSpec column,
            Comparison comparison, Object constant) {
        this.child = child;
        this.columnIndex = columnIndex;
        this.column = column;
        this.comparison = comparison;
        this.constant = constant;
    }

    /** The operator this filter pulls from. */
    public Operator child() {
        return child;
    }

    public long rowsIn() {
        return rowsIn;
    }

    public long rowsOut() {
        return rowsOut;
    }

    @Override
    public void open() {
        rowsIn = 0;
        rowsOut = 0;
        child.open();
    }

    @Override
    public Object[] next() {
        Object[] row;
        while ((row = child.next()) != null) {
            rowsIn++;
            if (matches(row[columnIndex])) {
                rowsOut++;
                return row;
            }
        }
        return null;
    }

    @Override
    public void close() {
        child.close();
        LOGGER.debug("column={} comparison={} rowsIn={} rowsOut={}",
                column.name(), comparison, rowsIn, rowsOut);
    }

    private boolean matches(Object value) {
        int cmp = MinMax.compare(column.type(), constant, value);
        return switch (comparison) {
            case EQUALS -> cmp == 0;
            case LESS_THAN -> cmp > 0;
            case GREATER_THAN -> cmp < 0;
        };
    }
}
