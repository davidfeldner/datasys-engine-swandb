package dk.itu.swandb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end tests of the SQL front door: a script runs through
 * parse → bind → plan → execute, each {@code SELECT}'s rows land on stdout
 * as headerless CSV, and errors land on stderr with stdout left clean.
 * Runs under the Failsafe plugin (mvn verify), not Surefire.
 */
class EngineIT {

    @Test
    void scriptPrintsHeaderlessCsvOnStdout(@TempDir Path tmp) throws Exception {
        Path csv = copyResource(tmp, "trips.csv");
        Path script = tmp.resolve("q.sql");
        Files.writeString(script, """
                CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                COPY trips FROM '%s';
                SELECT * FROM trips WHERE distance > 100;
                SELECT * FROM trips;
                """.formatted(csv));

        Capture capture = run(new String[]{"-f", script.toString()}, tmp);

        assertEquals(0, capture.exitCode(), capture.stderr());
        String expected = String.join("\n",
                "Aarhus,187,301.0",
                "Copenhagen,140,210.0",
                "Aalborg,210,340.5",
                "Esbjerg,299,450.25",
                "Copenhagen,12,23.5",
                "Aarhus,187,301.0",
                "Odense,95,120.75",
                "Copenhagen,140,210.0",
                "Aalborg,210,340.5",
                "Roskilde,31,45.0",
                "Copenhagen,88,99.99",
                "Esbjerg,299,450.25") + "\n";
        assertEquals(expected, capture.stdout());
    }

    @Test
    void aSingleStatementArgumentExecutes(@TempDir Path tmp) throws Exception {
        Path csv = copyResource(tmp, "trips.csv");
        // The create/copy pair has to run first; then the one-argument form.
        Path setup = scriptWith(tmp, csv, "CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);\n"
                + "COPY trips FROM '" + csv + "';\n");
        run(new String[]{"-f", setup.toString()}, tmp);

        Capture capture = run(new String[]{"SELECT * FROM trips WHERE city = 'Odense'"}, tmp);

        assertEquals(0, capture.exitCode(), capture.stderr());
        assertEquals("Odense,95,120.75\n", capture.stdout());
    }

    @Test
    void failingScriptWritesErrorToStderrAndLeavesStdoutClean(@TempDir Path tmp) throws Exception {
        Path script = tmp.resolve("bad.sql");
        Files.writeString(script, "SELECT * FROM missing;\n");

        Capture capture = run(new String[]{"-f", script.toString()}, tmp);

        assertEquals(1, capture.exitCode());
        assertEquals("", capture.stdout());
        assertTrue(capture.stderr().contains("unknown table: missing"), capture.stderr());
    }

    @Test
    void statementNumberCountsFromOneAndReturnsToZeroForStartAndStop(@TempDir Path tmp) throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "");
        String table = "t" + tag;
        Path csv = tmp.resolve("rows-" + tag + ".csv");
        Files.writeString(csv, "Copenhagen,12,23.5\n");
        Path script = tmp.resolve("numbered-" + tag + ".sql");
        Files.writeString(script, """
                CREATE TABLE %s (city STRING, distance LONG, price DOUBLE);
                COPY %s FROM '%s';
                SELECT * FROM %s WHERE distance > 100;
                SELECT * FROM %s;
                """.formatted(table, table, csv, table, table));

        assertEquals(0, run(new String[]{"-f", script.toString()}, tmp).exitCode());

        List<String> tagged = Files.readAllLines(Path.of("logs", "engine.log")).stream()
                .filter(line -> line.contains(tag))
                .toList();
        assertFalse(tagged.isEmpty(), "expected log lines for the probe tag " + tag);

        Set<String> numbers = tagged.stream()
                .map(line -> line.split(",", -1)[2])
                .collect(Collectors.toSet());
        assertEquals(Set.of("1", "2", "3", "4"), numbers,
                "every statement must carry its own number: " + tagged);

        // The engine's own start/stop lines are outside any statement.
        List<String> lifecycle = Files.readAllLines(Path.of("logs", "engine.log")).stream()
                .filter(line -> line.contains("engine started") || line.contains("engine stopped"))
                .toList();
        assertFalse(lifecycle.isEmpty());
        assertEquals("0", lifecycle.get(lifecycle.size() - 1).split(",", -1)[2]);
    }

    private static Path scriptWith(Path tmp, Path csv, String sql) throws Exception {
        Path script = tmp.resolve("setup.sql");
        Files.writeString(script, sql);
        return script;
    }

    private static Capture run(String[] args, Path tmp) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int exitCode = Engine.run(args, tmp.resolve("data"));
            return new Capture(exitCode, out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private static Path copyResource(Path tmp, String resourceName) throws Exception {
        Path target = tmp.resolve(resourceName);
        try (InputStream in = EngineIT.class.getClassLoader().getResourceAsStream(resourceName)) {
            assertTrue(in != null, "missing test resource " + resourceName);
            Files.write(target, in.readAllBytes());
        }
        return target;
    }

    private record Capture(int exitCode, String stdout, String stderr) {
    }
}
