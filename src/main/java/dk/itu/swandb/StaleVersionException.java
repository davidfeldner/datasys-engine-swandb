package dk.itu.swandb;

/**
 * A statement asked for a version that no longer exists. With the retention
 * rule of {@code docs/transactions-design.md} this is not a user error: it
 * means the engine dropped the version while a statement still needed it, so
 * it is reported as an {@link IllegalStateException}.
 */
public final class StaleVersionException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public StaleVersionException(String tableName, int version) {
        super("table " + tableName + " has no version " + version + " any more; it was retained too briefly");
    }
}
