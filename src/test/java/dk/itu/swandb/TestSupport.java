package dk.itu.swandb;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import dk.itu.swandb.enums.ColumnType;

/**
 * Shared fixtures for tests that need a classpath resource copied into a
 * temporary directory, and for tests that run the SQL front door and capture
 * its stdout and stderr.
 */
public final class TestSupport {

    /** The trips schema shared by every storage and planner test. */
    public static final List<ColumnSpec> TRIPS_SCHEMA = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    private TestSupport() {
    }

    /** Copies a test resource into {@code tmp} and returns the new file. */
    public static Path copyResource(Path tmp, String resourceName) throws Exception {
        Path target = tmp.resolve(resourceName);
        try (InputStream in = TestSupport.class.getClassLoader().getResourceAsStream(resourceName)) {
            assertNotNull(in, "missing test resource " + resourceName);
            Files.write(target, in.readAllBytes());
        }
        return target;
    }

    /** One engine run's exit code and captured output. */
    public record Capture(int exitCode, String stdout, String stderr) {
    }

    /**
     * Runs the front door against {@code dataDir} while capturing both
     * streams, so tests never print engine output into the build log.
     */
    public static Capture runEngine(String[] args, Path dataDir) {
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
}
