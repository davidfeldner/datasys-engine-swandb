package dk.itu.swandb.sql.ast;

/**
 * One {@code leftColumn = rightColumn} predicate: the equi-join condition of
 * Exercise 10. Column names stay unqualified this week; the binder resolves
 * each side against the schemas of the {@code FROM} list.
 */
public record JoinPredicate(String leftColumn, String rightColumn) {

    public JoinPredicate {
        if (leftColumn == null || leftColumn.isEmpty())
            throw new IllegalArgumentException("join left column name must not be empty");
        if (rightColumn == null || rightColumn.isEmpty())
            throw new IllegalArgumentException("join right column name must not be empty");
    }
}
