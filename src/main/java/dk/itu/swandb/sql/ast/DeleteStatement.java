package dk.itu.swandb.sql.ast;

import java.util.Objects;
import java.util.Optional;

/**
 * {@code DELETE FROM table} with an optional {@code WHERE}, exactly the
 * shape of {@link SelectStatement}: an absent WHERE means every row is
 * deleted and is carried as {@link Optional#empty()}.
 */
public record DeleteStatement(String tableName, Optional<Predicate> where)
        implements Statement {

    public DeleteStatement {
        Objects.requireNonNull(tableName, "tableName");
        where = Objects.requireNonNull(where, "where");
    }
}
