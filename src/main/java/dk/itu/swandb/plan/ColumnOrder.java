package dk.itu.swandb.plan;

import java.util.List;

import dk.itu.swandb.ColumnSpec;

/**
 * Puts a joined row's columns back into {@code FROM}-list order.
 *
 * <p>A hash join emits the build row's columns followed by the probe row's.
 * While the build side is always the intermediate result, that concatenation
 * is the {@code FROM}-list order; but Exercise 11 lets the smaller estimated
 * input build, and then a later table's columns can end up before an earlier
 * table's. {@code SELECT *} must not depend on that, so the planner wraps the
 * chain in one {@link ColumnReorderOperator} whenever the row order it built
 * differs from the {@code FROM} list.
 */
final class ColumnOrder {

    private ColumnOrder() {
    }

    /**
     * {@code root} itself when its rows already follow the FROM list,
     * otherwise a reordering pass over it.
     *
     * @param rowOrder the tables whose columns make up each row, in row order
     * @param tables   the pinned tables in {@code FROM}-list order
     */
    static Operator restore(Operator root, List<Integer> rowOrder, List<Planner.TableInput> tables) {
        int[] source = sourcePositions(rowOrder, tables);
        if (ColumnReorderOperator.isIdentity(source)) return root;
        return new ColumnReorderOperator(root, source);
    }

    /**
     * For each {@code FROM}-list column position, the row position it
     * occupies in a row laid out as {@code rowOrder}.
     */
    static int[] sourcePositions(List<Integer> rowOrder, List<Planner.TableInput> tables) {
        int[] startAt = new int[tables.size()];
        int width = 0;
        for (int table : rowOrder) {
            startAt[table] = width;
            width += columnsOf(tables, table).size();
        }
        int total = 0;
        for (Planner.TableInput table : tables) total += table.columns().size();
        if (width != total)
            throw new IllegalStateException("the row order names " + width
                    + " columns but the FROM list has " + total);
        int[] source = new int[total];
        int fromPosition = 0;
        for (int table = 0; table < tables.size(); table++) {
            for (int c = 0; c < tables.get(table).columns().size(); c++) {
                source[fromPosition++] = startAt[table] + c;
            }
        }
        return source;
    }

    private static List<ColumnSpec> columnsOf(List<Planner.TableInput> tables, int table) {
        if (table < 0 || table >= tables.size())
            throw new IllegalArgumentException("row order names table " + table + " outside the FROM list");
        return tables.get(table).columns();
    }
}
