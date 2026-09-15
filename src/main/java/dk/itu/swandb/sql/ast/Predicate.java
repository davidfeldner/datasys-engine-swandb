package dk.itu.swandb.sql.ast;

import java.util.Optional;

import dk.itu.swandb.enums.Comparison;

/**
 * One {@code column op constant} predicate. The constant's Java type follows
 * the literal that produced it: {@code String}, {@code Long} or
 * {@code Double}, exactly the types {@code StorageEngine.select} demands.
 */
public record Predicate(String columnName, Comparison comparison, Object constant) {

    public Predicate {
        if (columnName == null || columnName.isEmpty())
            throw new IllegalArgumentException("predicate column name must not be empty");
        if (comparison == null)
            throw new IllegalArgumentException("predicate comparison must not be null");
    }
}
