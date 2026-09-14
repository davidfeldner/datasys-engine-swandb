package dk.itu.swandb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the CSV log contract: one line per API call including failures,
 * exactly seven comma-delimited values per line, and a populated
 * {@code sessionId}/{@code statementNumber} context even when the storage
 * API is driven directly rather than through {@code Engine.main}.
 *
 * <p>Only the lines written by this test are inspected, so pre-existing
 * content in the shared log file cannot affect the result.
 */
class LoggingIT {

    private static final List<ColumnSpec> SCHEMA = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    private static Path logFile() {
        return Path.of("logs", "engine.log");
    }

    private static long logSize() throws Exception {
        Path log = logFile();
        return Files.exists(log) ? Files.size(log) : 0L;
    }

    /** Return the log lines appended after byte offset {@code from}. */
    private static List<String> linesAfter(long from) throws Exception {
        byte[] all = Files.readAllBytes(logFile());
        // If the file rolled over between the snapshot and now, fall back to
        // reading whatever is present rather than throwing.
        int start = (int) Math.min(from, all.length);
        String tail = new String(all, start, all.length - start, StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<>();
        for (String line : tail.split("\n")) {
            if (!line.isEmpty()) lines.add(line);
        }
        return lines;
    }

    @Test
    void everyCallLogsOneSevenFieldLineIncludingFailures(@TempDir Path tmp) throws Exception {
        Path csv = tmp.resolve("trips.csv");
        try (InputStream in = LoggingIT.class.getClassLoader().getResourceAsStream("trips.csv")) {
            Files.write(csv, in.readAllBytes());
        }

        long before = logSize();

        StorageEngine engine = new StorageEngine(tmp);
        engine.createTable("trips", SCHEMA);              // succeeds
        engine.copyFile("trips", csv.toString());         // succeeds

        // A failing call: duplicate table.
        assertThrows(IllegalArgumentException.class, () -> engine.createTable("trips", SCHEMA));

        // A successful call whose constant contains a comma, plus a failing
        // call whose error message contains a comma.
        engine.select("trips", "city", Comparison.EQUALS, "A,B");
        assertThrows(IllegalArgumentException.class,
                () -> engine.select("trips", "ci,ty", Comparison.EQUALS, "x"));

        List<String> lines = linesAfter(before);
        assertFalse(lines.isEmpty(), "expected the API calls to append log lines");

        // Contract 1: every line parses into exactly seven positional values.
        for (String line : lines) {
            assertEquals(7, line.split(",", -1).length, "not seven fields: " + line);
        }

        // Contract 2: failing calls are logged too.
        assertTrue(lines.stream().anyMatch(l -> l.contains("operation=createTable")
                        && l.contains("status=FAILED")),
                "no failure line for the rejected createTable");
        assertTrue(lines.stream().anyMatch(l -> l.contains("operation=select")
                        && l.contains("status=FAILED")),
                "no failure line for the rejected select");
        assertTrue(lines.stream().anyMatch(l -> l.contains("status=FAILED")
                        && l.contains("error=unknown column: ci;ty")),
                "failure line did not sanitize the comma in the error message");

        // Contract 3: log context is populated for direct API use.
        long storageEngineLines = 0;
        for (String line : lines) {
            String[] f = line.split(",", -1);
            if (!f[5].equals("StorageEngine")) continue;
            storageEngineLines++;
            assertFalse(f[1].isEmpty(), "empty sessionId: " + line);
            assertEquals("0", f[2], "statementNumber must be 0: " + line);
        }
        assertTrue(storageEngineLines > 0, "expected StorageEngine lines in the log");
    }
}
