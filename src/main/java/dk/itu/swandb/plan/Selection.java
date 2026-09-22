package dk.itu.swandb.plan;

import dk.itu.swandb.enums.Comparison;

/**
 * The predicate the planner must turn into pruning decisions and, if any
 * partition survives, a {@link FilterOperator}. It is the storage-level
 * spelling of a {@code WHERE column op constant}: the SQL layer converts a
 * bound {@code Predicate} into one of these so the planner never depends on
 * the SQL AST.
 */
public record Selection(String columnName, Comparison comparison, Object constant) {

    public Selection {
        if (columnName == null || columnName.isEmpty())
            throw new IllegalArgumentException("selection column name must not be empty");
        if (comparison == null)
            throw new IllegalArgumentException("selection comparison must not be null");
    }
}
