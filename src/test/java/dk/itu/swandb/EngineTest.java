package dk.itu.swandb;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals("", capture.stderr());
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
