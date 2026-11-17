package dk.itu.swandb.sql.ast;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code SELECT * FROM table, ...} with an optional conjunction of
 * predicates. The two predicate shapes are kept apart: {@code filters} hold
 * {@code column op constant}, {@code joins} hold {@code column = column}.
 * Both lists keep the order they were written in, so printing them back in a
 * fixed order (filters first, then joins) still satisfies the round-trip
 * property {@code parse(print(s)).equals(s)}.
 *
 * <p>The convenience constructor for the Part 1 shape
 * ({@code tableName, where}) is kept so a single-table statement can still
 * be built and compared with the short form.
 */
public record SelectStatement(List<String> tableNames, List<Predicate> filters,
        List<JoinPredicate> joins) implements Statement {

    public SelectStatement {
        tableNames = List.copyOf(Objects.requireNonNull(tableNames, "tableNames"));
        filters = List.copyOf(Objects.requireNonNull(filters, "filters"));
        joins = List.copyOf(Objects.requireNonNull(joins, "joins"));
        if (tableNames.isEmpty())
            throw new IllegalArgumentException("a SELECT needs at least one table");
    }

    /** The single-table shape of Part 1: one table, one optional filter. */
    public SelectStatement(String tableName, Optional<Predicate> where) {
        this(List.of(Objects.requireNonNull(tableName, "tableName")),
                filtersOf(Objects.requireNonNull(where, "where")), List.of());
    }

    private static List<Predicate> filtersOf(Optional<Predicate> where) {
        return where.isPresent() ? List.of(where.get()) : List.of();
    }
}
