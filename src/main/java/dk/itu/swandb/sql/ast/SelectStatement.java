package dk.itu.swandb.sql.ast;

import java.util.List;
import java.util.Objects;

/**
 * {@code SELECT * FROM table, ...} with an optional conjunction of
 * predicates. The two predicate shapes are kept apart: {@code filters} hold
 * {@code column op constant}, {@code joins} hold {@code column = column}.
 * Both lists keep the order they were written in, so printing them back in a
 * fixed order (filters first, then joins) still satisfies the round-trip
 * property {@code parse(print(s)).equals(s)}.
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
}
