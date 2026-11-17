package dk.itu.swandb.sql.ast;

import java.util.Objects;
import java.util.Optional;

/**
 * {@code DELETE FROM table} with an optional {@code WHERE}. A DELETE still
 * touches one table and compares one column with one constant: the
 * column-to-column shape of Exercise 10 is a {@code SELECT} join predicate
 * only. An absent WHERE means every row is deleted and is carried as
 * {@link Optional#empty()}.
 */
public record DeleteStatement(String tableName, Optional<Predicate> where)
        implements Statement {

    public DeleteStatement {
        Objects.requireNonNull(tableName, "tableName");
        where = Objects.requireNonNull(where, "where");
    }
}
