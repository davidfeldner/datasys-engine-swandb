package dk.itu.swandb.plan;

/**
 * The storage-level spelling of a {@code column = column} predicate, the
 * sibling of {@link Selection}. The SQL layer converts a bound
 * {@code JoinPredicate} into one of these so the planner never depends on
 * the SQL AST.
 */
public record JoinCondition(String leftColumn, String rightColumn) {

    public JoinCondition {
        if (leftColumn == null || leftColumn.isEmpty())
            throw new IllegalArgumentException("join left column name must not be empty");
        if (rightColumn == null || rightColumn.isEmpty())
            throw new IllegalArgumentException("join right column name must not be empty");
    }
}
