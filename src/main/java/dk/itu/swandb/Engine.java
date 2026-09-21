package dk.itu.swandb;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import dk.itu.swandb.sql.Executor;

/**
 * SQL front door of the engine. Three modes:
 *
 * <ul>
 *   <li>no arguments — print the team name and usage;</li>
 *   <li>one argument — run that single SQL statement
 *       ({@code -Dexec.args="'SELECT * FROM trips'"});</li>
 *   <li>{@code -f <script.sql>} — run a whole script.</li>
 * </ul>
 *
 * <p>Every {@code SELECT}'s rows go to stdout as headerless CSV and nothing
 * else does; the console log and errors go to stderr, so
 * {@code > ours.csv} captures exactly the query result. The data directory
 * defaults to {@code data/} under the working directory.
 */
public final class Engine {

    private static final Logger LOGGER = LoggerFactory.getLogger(Engine.class);

    /** Team name shown by the usage banner. */
    static final String TEAM_NAME = "Swandb";

    /** Data directory used by {@link #main}; {@link #run} takes an override. */
    static final Path DEFAULT_DATA_DIR = Path.of("data");

    private Engine() {
    }

    public static void main(String[] args) {
        int exitCode = run(args, DEFAULT_DATA_DIR);
        if (exitCode != 0)
            System.exit(exitCode);
    }

    /**
     * Runs the front door and returns the process exit code (0 on success).
     * {@code main} turns a non-zero code into {@code System.exit}; tests call
     * this directly so a failing script cannot kill their JVM.
     */
    public static int run(String[] args, Path dataDir) {
        MDC.put("sessionId", UUID.randomUUID().toString());
        MDC.put("statementNumber", "0");
        long start = System.nanoTime();
        try {
            LOGGER.debug("engine started dataDir={}", LogFormat.sanitize(dataDir));

            if (args.length == 0) {
                printUsage(System.out);
                return 0;
            }

            String sql = scriptText(args);
            if (sql == null) {
                printUsage(System.err);
                return 2;
            }

            StorageEngine engine = new StorageEngine(dataDir);
            new Executor(engine).executeScript(sql,
                    rows -> CsvWriter.writeRows(System.out, rows));
            return 0;
        } catch (RuntimeException | IOException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            System.err.println("error: " + message);
            LOGGER.error("engine failed reason={} durationMs={}",
                    LogFormat.sanitize(message), elapsedMs(start));
            return 1;
        } finally {
            // Back to 0 so the stop line is outside any statement's count.
            MDC.put("statementNumber", "0");
            LOGGER.debug("engine stopped durationMs={}", elapsedMs(start));
            MDC.remove("statementNumber");
            MDC.remove("sessionId");
        }
    }

    /** The SQL to run, or {@code null} when the arguments name no valid mode. */
    private static String scriptText(String[] args) throws IOException {
        if (args.length == 1)
            return terminate(args[0]);
        if (args.length == 2 && "-f".equals(args[0]))
            return Files.readString(Path.of(args[1]));
        return null;
    }

    /**
     * The one-statement mode accepts the statement with or without its
     * trailing semicolon, mirroring DuckDB's CLI; the grammar itself still
     * demands the terminator, so a bare statement is closed here.
     */
    private static String terminate(String statement) {
        String trimmed = statement.trim();
        return trimmed.endsWith(";") ? statement : statement + ";";
    }

    private static void printUsage(PrintStream out) {
        out.println(TEAM_NAME + " — How to Build Data Systems, Fall 2026");
        out.println("usage:");
        out.println("  mvn exec:java                            print this message");
        out.println("  mvn exec:java -Dexec.args=\"'<sql>'\"      run one SQL statement");
        out.println("  mvn exec:java -Dexec.args=\"-f <file>\"    run a .sql script");
        out.println();
        out.println("SELECT rows are written to stdout as headerless CSV; logs go to stderr.");
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
