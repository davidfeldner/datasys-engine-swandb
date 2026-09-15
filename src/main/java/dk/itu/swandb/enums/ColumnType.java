package dk.itu.swandb.enums;

/**
 * Supported column types in the storage engine.
 */
public enum ColumnType {
    STRING,
    LONG,
    DOUBLE;

    /**
     * The Java class a constant compared against a column of this type must
     * have. The match is exact, so an {@code Integer} is not a {@code LONG}
     * constant. Shared by the engine's own validation and by the SQL binder,
     * so that both reject the same constants.
     */
    public Class<?> javaType() {
        return switch (this) {
            case STRING -> String.class;
            case LONG -> Long.class;
            case DOUBLE -> Double.class;
        };
    }

    /** Whether {@code constant} has the exact Java type this column demands. */
    public boolean accepts(Object constant) {
        return constant != null && javaType().isInstance(constant);
    }
}
