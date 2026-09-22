package dk.itu.swandb;

import java.io.PrintStream;
import java.util.List;

/**
 * Renders query rows as headerless CSV, the front door's stdout format.
 * Quoting follows the usual CSV rule: a field is quoted when it contains a
 * comma, a quote, or a line break, and an embedded quote is doubled. Plain
 * values are written unchanged, so a numeric column prints exactly as
 * {@code Long.toString}/{@code Double.toString} spell it.
 */
public final class CsvWriter {

    private CsvWriter() {
    }

    /** Writes each row on its own line, in order. */
    public static void writeRows(PrintStream out, List<Object[]> rows) {
        for (Object[] row : rows) {
            out.println(formatRow(row));
        }
    }

    /** Renders one row as a single CSV line, without the line terminator. */
    public static String formatRow(Object[] row) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < row.length; i++) {
            if (i > 0) line.append(',');
            line.append(formatValue(row[i]));
        }
        return line.toString();
    }

    private static String formatValue(Object value) {
        if (value == null)
            return "";
        String text = value.toString();
        if (!needsQuoting(text))
            return text;
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    private static boolean needsQuoting(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ',' || c == '"' || c == '\n' || c == '\r')
                return true;
        }
        return false;
    }
}
