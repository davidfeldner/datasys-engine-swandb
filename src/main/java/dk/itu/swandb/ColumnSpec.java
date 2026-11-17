package dk.itu.swandb;

import dk.itu.swandb.enums.ColumnType;

/**
 * Names and types of one column in a table.
 */
public record ColumnSpec(String name, ColumnType type) {
    public ColumnSpec {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("column name must not be empty");
        if (type == null) throw new IllegalArgumentException("column type must not be null");
    }

    /**
     * Rejects a constant this column cannot be compared against: it must be
     * non-null and have the exact Java type the column demands. Shared by the
     * SQL binder, the planner and the engine API so all three accept exactly
     * the same constants.
     */
    public void requireConstant(Object constant) {
        if (constant == null)
            throw new IllegalArgumentException("constant must not be null for column " + name);
        if (!type.accepts(constant))
            throw new IllegalArgumentException(
                    "constant type mismatch for column " + name + ": expected " + type
                            + " got " + constant.getClass().getSimpleName());
    }
}
