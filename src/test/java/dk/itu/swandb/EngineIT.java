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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /**
     * Required test of Exercise 5: a failing statement must leave an
     * {@code ERROR} line for it in {@code logs/engine.log}, so the failure is
     * visible when the engine later reads its own log. A unique tag in the
     * table name isolates this run's lines from every other line in the file.
     */
    @Test
    void failingStatementLeavesAnErrorLineInTheLog(@TempDir Path tmp) throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "");
        Path script = tmp.resolve("fail-" + tag + ".sql");
        Files.writeString(script, "SELECT * FROM missing_" + tag + ";\n");

        Capture capture = run(new String[]{"-f", script.toString()}, tmp);
        assertEquals(1, capture.exitCode());

        List<String> errorLines = Files.readAllLines(Path.of("logs", "engine.log")).stream()
                .filter(line -> line.contains(tag))
                .filter(line -> line.split(",", -1)[4].equals("ERROR"))
                .toList();
        assertFalse(errorLines.isEmpty(),
                "expected an ERROR line for the failing statement, tag " + tag);
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

    /**
     * Required test of Exercise 7: one script with CREATE, COPY, COPY, DELETE
     * and SELECT runs through the front door and returns the rows the two
     * COPY statements loaded minus the ones the DELETE removed.
     */
    @Test
    void deleteThroughTheFrontDoorReturnsTheCopiesMinusTheDeletedRows(@TempDir Path tmp) throws Exception {
        Path csv = copyResource(tmp, "trips.csv");
        Path script = tmp.resolve("dml.sql");
        Files.writeString(script, """
                CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                COPY trips FROM '%s';
                COPY trips FROM '%s';
                DELETE FROM trips WHERE city = 'Copenhagen';
                SELECT * FROM trips;
                """.formatted(csv, csv));

        Capture capture = run(new String[]{"-f", script.toString()}, tmp);

        assertEquals(0, capture.exitCode(), capture.stderr());
        // 16 loaded rows minus the 3 Copenhagen rows in each load.
        List<String> lines = capture.stdout().lines().toList();
        assertEquals(10, lines.size(), capture.stdout());
        assertFalse(capture.stdout().contains("Copenhagen"), capture.stdout());
        // Order is preserved: the surviving rows of the first load, then the second.
        assertEquals(List.of(
                "Aarhus,187,301.0",
                "Odense,95,120.75",
                "Aalborg,210,340.5",
                "Roskilde,31,45.0",
                "Esbjerg,299,450.25",
                "Aarhus,187,301.0",
                "Odense,95,120.75",
                "Aalborg,210,340.5",
                "Roskilde,31,45.0",
                "Esbjerg,299,450.25"), lines);
    }

    @Test
    void deleteWithoutWhereEmptiesTheTableThroughTheFrontDoor(@TempDir Path tmp) throws Exception {
        Path csv = copyResource(tmp, "trips.csv");
        Path script = tmp.resolve("truncate.sql");
        Files.writeString(script, """
                CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                COPY trips FROM '%s';
                DELETE FROM trips;
                SELECT * FROM trips;
                """.formatted(csv));

        Capture capture = run(new String[]{"-f", script.toString()}, tmp);

        assertEquals(0, capture.exitCode(), capture.stderr());
        assertEquals("", capture.stdout(), "the table is empty and DELETE printed nothing");
    }

    /**
     * Every statement's summary line carries the version it committed or read,
     * in the form the exercise fixes.
     */
    @Test
    void everySummaryLineReportsItsVersion(@TempDir Path tmp) throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "");
        String table = "t" + tag;
        Path csv = copyResource(tmp, "trips.csv");
        Path script = tmp.resolve("versions-" + tag + ".sql");
        Files.writeString(script, """
                CREATE TABLE %s (city STRING, distance LONG, price DOUBLE);
                COPY %s FROM '%s';
                COPY %s FROM '%s';
                DELETE FROM %s WHERE city = 'Copenhagen';
                SELECT * FROM %s;
                """.formatted(table, table, csv, table, csv, table, table));

        assertEquals(0, run(new String[]{"-f", script.toString()}, tmp).exitCode());

        // The executor's summary lines are the ones whose message starts with
        // statement=KIND and carries a version.
        String summary = "statement=(CREATE|COPY|DELETE|SELECT) table=.*version=\\d+.*";
        List<String> messages = Files.readAllLines(Path.of("logs", "engine.log")).stream()
                .filter(line -> line.contains(table))
                .filter(line -> line.split(",", -1)[5].equals("Executor"))
                .map(line -> line.split(",", -1)[6])
                .filter(message -> message.matches(summary))
                .toList();
        assertFalse(messages.isEmpty(), "expected summary lines for " + table);

        List<String> kinds = new ArrayList<>();
        List<Integer> versions = new ArrayList<>();
        Pattern versionField = Pattern.compile("version=(\\d+)");
        for (String message : messages) {
            kinds.add(message.substring("statement=".length(), message.indexOf(' ')));
            Matcher m = versionField.matcher(message);
            assertTrue(m.find(), message);
            versions.add(Integer.parseInt(m.group(1)));
        }
        assertEquals(List.of("CREATE", "COPY", "COPY", "DELETE", "SELECT"), kinds, messages.toString());
        assertEquals(List.of(0, 1, 2, 3, 3), versions, messages.toString());
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
