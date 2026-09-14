package dk.itu.swandb.sql.ast;

import java.util.Objects;
import java.util.Optional;

/**
 * {@code SELECT * FROM table} with an optional {@code WHERE}. An absent
 * WHERE is carried as {@link Optional#empty()}, so the record's generated
 * {@code equals} covers both shapes.
 */
public record SelectStatement(String tableName, Optional<Predicate> where)
        implements Statement {

    public SelectStatement {
        Objects.requireNonNull(tableName, "tableName");
        where = Objects.requireNonNull(where, "where");
    }
}
