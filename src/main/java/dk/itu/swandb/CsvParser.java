package dk.itu.swandb;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a headerless CSV file positionally into a list of rows. Each row
 * is an {@code Object[]} with one value per schema column, parsed to the
 * type the schema declares. Lines with the wrong field count, or values
 * that fail to parse to the declared type, fail the entire load.
 */
public final class CsvParser {

    private CsvParser() {
    }

    /** Parse a single CSV line into typed values for the given schema. */
    public static Object[] parseLine(String line, int lineNumber, String fileName,
                                     List<ColumnSpec> schema) {
        String[] fields = line.split(",", -1);
        if (fields.length != schema.size()) {
            throw new IllegalArgumentException(
                    "malformed CSV in " + fileName + " line " + lineNumber
                            + ": expected " + schema.size() + " fields but got " + fields.length);
        }
        Object[] row = new Object[schema.size()];
        for (int i = 0; i < schema.size(); i++) {
            row[i] = parseValue(fields[i], schema.get(i), fileName, lineNumber, i);
        }
        return row;
    }

    private static Object parseValue(String raw, ColumnSpec spec, String fileName,
                                     int lineNumber, int columnIndex) {
        String field = raw.trim();
        try {
            return switch (spec.type()) {
                case STRING -> field;
                case LONG -> Long.parseLong(field);
                case DOUBLE -> Double.parseDouble(field);
            };
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "malformed CSV in " + fileName + " line " + lineNumber
                            + " column " + columnIndex + " (" + spec.name()
                            + "): cannot parse '" + field + "' as " + spec.type(), e);
        }
    }

    /** Read the whole CSV file into rows. */
    public static List<Object[]> read(Path csvFile, List<ColumnSpec> schema)
            throws IOException {
        List<Object[]> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(csvFile)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                rows.add(parseLine(line, lineNumber, csvFile.toString(), schema));
            }
        }
        return rows;
    }
}
