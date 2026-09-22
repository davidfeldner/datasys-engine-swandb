package dk.itu.swandb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The front door with no arguments is not a query mode: it prints the team
 * name and how to use the engine, and produces no CSV.
 */
class EngineTest {

    @Test
    void noArgumentsPrintsTeamNameAndUsage(@TempDir Path tmp) {
        Capture capture = run(new String[0], tmp);

        assertEquals(0, capture.exitCode());
        assertTrue(capture.stdout().contains(Engine.TEAM_NAME),
                "stdout should name the team: " + capture.stdout());
        assertTrue(capture.stdout().contains("usage"), capture.stdout());
        assertTrue(capture.stdout().contains("-f"), capture.stdout());
        // The console log legitimately goes to stderr, so stderr need not be
        // empty; what matters is that the usage path reports no error and that
        // no log line ever reaches stdout. Asserting on an empty stderr would
        // also depend on whether Log4j2 bound System.err before this test
        // redirected it, and would fail when the class runs on its own.
        assertFalse(capture.stderr().contains("error:"), capture.stderr());
        assertFalse(capture.stdout().contains("DEBUG"), capture.stdout());
    }

    @Test
    void aMissingScriptFileReportsAReadableError(@TempDir Path tmp) {
        Path missing = tmp.resolve("nope.sql");

        Capture capture = run(new String[]{"-f", missing.toString()}, tmp);

        assertEquals(1, capture.exitCode());
        assertEquals("", capture.stdout());
        assertTrue(capture.stderr().contains("cannot read SQL script: " + missing), capture.stderr());
    }

    private static Capture run(String[] args, Path dataDir) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int exitCode = Engine.run(args, dataDir);
            return new Capture(exitCode, out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record Capture(int exitCode, String stdout, String stderr) {
    }
}
