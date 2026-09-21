package dk.itu.swandb;

/**
 * Formatting helper shared by every class that writes the seven-field CSV
 * log line. A comma inside a message would split the line into an extra
 * field, so every interpolated value must pass through {@link #sanitize};
 * see {@code docs/csv-logging.md}.
 */
public final class LogFormat {

    private LogFormat() {
    }

    /** Replaces commas so the value cannot break the CSV log line. */
    public static String sanitize(Object value) {
        if (value == null)
            return "null";
        return value.toString().replace(',', ';');
    }
}
