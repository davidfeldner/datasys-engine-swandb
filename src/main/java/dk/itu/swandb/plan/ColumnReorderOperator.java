package dk.itu.swandb.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * Reorders a joined row into {@code FROM}-list column order.
 *
 * <p>A hash join emits the build row's columns followed by the probe row's,
 * which is {@code FROM}-list order only while the chain also follows the
 * {@code FROM} list. The optimized planner may join the tables in any order,
 * so it wraps the finished chain in one prevention pass: the row positions
 * the columns occupy in the plan are mapped back to their {@code FROM}-list
 * positions, and {@code SELECT *} then emits the same columns in the same
 * order under both join modes (Exercise 11, section 4).
 *
 * <p>The planner only builds this operator when the mapping is not the
 * identity, so a plan whose row is already in {@code FROM} order carries no
 * reordering pass at all.
 */
public final class ColumnReorderOperator implements Operator {

    private final Operator child;
    /** For each output position, the child row position its value comes from. */
    private final int[] source;

    ColumnReorderOperator(Operator child, int[] source) {
        this.child = child;
        this.source = source.clone();
    }

    /** Whether {@code source} is the identity: no reordering needed. */
    public static boolean isIdentity(int[] source) {
        for (int i = 0; i < source.length; i++) {
            if (source[i] != i) return false;
        }
        return true;
    }

    @Override
    public void open() {
        child.open();
    }

    @Override
    public Object[] next() {
        Object[] row = child.next();
        if (row == null) return null;
        Object[] out = new Object[source.length];
        for (int i = 0; i < source.length; i++) {
            out[i] = row[source[i]];
        }
        return out;
    }

    @Override
    public void close() {
        child.close();
    }

    /** The operator below the reordering pass: the planner's tests walk it. */
    Operator child() {
        return child;
    }
}
