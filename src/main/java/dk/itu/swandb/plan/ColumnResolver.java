package dk.itu.swandb.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dk.itu.swandb.ColumnSpec;

/**
 * Resolves an unqualified column name against the schemas of a {@code FROM}
 * list, the one answer both the binder and the planner need: which table
 * owns the column, and where the column sits in the concatenated joined row.
 *
 * <p>Columns are concatenated in {@code FROM}-list order, so a join built
 * left to right emits exactly that row. A name that occurs in no table is
 * unknown; a name that occurs in two or more tables (or twice in one) is
 * ambiguous, and both are {@link IllegalArgumentException}s, matching the
 * SQL layer's error family.
 */
public final class ColumnResolver {

    /**
     * One column's home: its table, its position inside that table, and its
     * position in the concatenated row.
     */
    public record ResolvedColumn(int tableIndex, String tableName, int tableColumnIndex,
            int rowIndex, ColumnSpec column) {
    }

    private final List<String> tableNames;
    private final List<List<ColumnSpec>> schemas;
    private final int[] tableOffsets;
    private final int rowWidth;

    /**
     * @param tableNames the table names in {@code FROM}-list order
     * @param schemas    each table's schema, in the same order
     */
    public ColumnResolver(List<String> tableNames, List<List<ColumnSpec>> schemas) {
        Objects.requireNonNull(tableNames, "tableNames");
        Objects.requireNonNull(schemas, "schemas");
        if (tableNames.isEmpty())
            throw new IllegalArgumentException("a FROM list must name at least one table");
        if (tableNames.size() != schemas.size())
            throw new IllegalArgumentException("table names and schemas must have the same size");

        this.tableNames = List.copyOf(tableNames);
        List<List<ColumnSpec>> copied = new ArrayList<>(schemas.size());
        this.tableOffsets = new int[schemas.size()];
        int width = 0;
        for (int t = 0; t < schemas.size(); t++) {
            List<ColumnSpec> schema = List.copyOf(Objects.requireNonNull(schemas.get(t), "schema"));
            tableOffsets[t] = width;
            width += schema.size();
            copied.add(schema);
        }
        this.schemas = List.copyOf(copied);
        this.rowWidth = width;
    }

    /** The table names in {@code FROM}-list order. */
    public List<String> tableNames() {
        return tableNames;
    }

    /** One table's schema, indexed by its {@code FROM}-list position. */
    public List<ColumnSpec> schema(int tableIndex) {
        return schemas.get(tableIndex);
    }

    /** The first row index that belongs to {@code tableIndex}. */
    public int tableOffset(int tableIndex) {
        return tableOffsets[tableIndex];
    }

    /** The width of the concatenated row. */
    public int rowWidth() {
        return rowWidth;
    }

    /**
     * The one table owning {@code columnName} and the column's position in
     * the concatenated row.
     *
     * @throws IllegalArgumentException if the name is unknown or ambiguous
     */
    public ResolvedColumn resolve(String columnName) {
        if (columnName == null || columnName.isEmpty())
            throw new IllegalArgumentException("column name must not be empty");

        ResolvedColumn found = null;
        for (int t = 0; t < schemas.size(); t++) {
            List<ColumnSpec> schema = schemas.get(t);
            for (int c = 0; c < schema.size(); c++) {
                if (!schema.get(c).name().equals(columnName))
                    continue;
                if (found != null)
                    throw new IllegalArgumentException("ambiguous column: " + columnName
                            + " in tables " + found.tableName() + ", " + tableNames.get(t));
                found = new ResolvedColumn(t, tableNames.get(t), c,
                        tableOffsets[t] + c, schema.get(c));
            }
        }
        if (found == null)
            throw new IllegalArgumentException("unknown column: " + columnName);
        return found;
    }
}
