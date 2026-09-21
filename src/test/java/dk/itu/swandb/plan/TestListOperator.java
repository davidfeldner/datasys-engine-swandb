package dk.itu.swandb.plan;

import java.util.List;

/**
 * Minimal test helper: serves rows from an in-memory list. Used to exercise
 * {@link FilterOperator} without any storage.
 */
final class TestListOperator implements Operator {

    private final List<Object[]> rows;
    private int index;

    TestListOperator(List<Object[]> rows) {
        this.rows = List.copyOf(rows);
    }

    @Override
    public void open() {
        index = 0;
    }

    @Override
    public Object[] next() {
        return index < rows.size() ? rows.get(index++) : null;
    }

    @Override
    public void close() {
        // nothing to release
    }
}
