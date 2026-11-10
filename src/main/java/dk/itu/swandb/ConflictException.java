package dk.itu.swandb;

/**
 * A statement's commit found that another writer committed first: the base
 * version it pinned is no longer the newest. First committer wins, so the
 * loser aborts with this {@link RuntimeException} and leaves no visible
 * change; nothing it wrote is reachable until the swap, and the next commit
 * reclaims any bytes it appended.
 *
 * <p>The message is the conflict line of Exercise 8, for example
 * {@code conflict table=trips base=3 current=4}. The SQL front door prints it
 * to stderr and the executor leaves it in the {@code ERROR} log line.
 */
public final class ConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConflictException(String tableName, int baseVersion, int currentVersion) {
        super("conflict table=" + tableName + " base=" + baseVersion + " current=" + currentVersion);
    }
}
