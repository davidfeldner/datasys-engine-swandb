package dk.itu.swandb;

/**
 * Names and types of one column in a table.
 */
public record ColumnSpec(String name, ColumnType type) {
    public ColumnSpec {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("column name must not be empty");
        }
        if (type == null) {
            throw new IllegalArgumentException("column type must not be null");
        }
    }
}
