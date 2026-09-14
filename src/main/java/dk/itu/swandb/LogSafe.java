package dk.itu.swandb;

/**
 * Renders values into log-message tokens that never contain a comma.
 *
 * The CSV log format is comma-delimited with exactly seven positional
 * values, so a comma anywhere inside a message would split one line into
 * more than seven values and break the layout that week 5 loads back in
 * with {@code COPY}. Every value that reaches a log message is passed
 * through {@link #value(Object)} to guarantee that invariant.
 *
 * A comma is replaced by a semicolon. This is lossy, but it cannot lose
 * real data: course CSV input has no quoted fields or embedded commas,
 * so the only values that can carry a comma are table names, column
 * names, file paths, and programmatically supplied constants.
 */
final class LogSafe {

    private LogSafe() {
    }

    /** Render {@code value} as a log token containing no comma. */
    static String value(Object value) {
        if (value == null) return "null";
        return value.toString().replace(',', ';');
    }
}
