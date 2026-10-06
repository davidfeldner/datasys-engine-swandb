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
 *   <li>{@code -c "<sql>"} — run that single SQL statement (a bare
 *       {@code "<sql>"} argument is accepted as shorthand);</li>
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

    /**
     * System property that sets the partition row cap for tables this process
     * creates, overriding {@link StorageEngine#DEFAULT_MAX_ROWS_PER_PARTITION}.
     * The experiment design ({@code docs/experiment-design.md}) sweeps it
     * through {@code ENGINE_JAVA_OPTS}; the cap is captured into the catalog
     * at {@code CREATE TABLE} time and used by the following {@code COPY}.
     */
    static final String MAX_ROWS_PER_PARTITION_PROPERTY = "engine.maxRowsPerPartition";

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

            StorageEngine engine = new StorageEngine(dataDir, maxRowsPerPartition());
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
        if (args.length == 1) {
            // A bare "-f" or "-c" names no script; show usage instead of
            // handing "-f;" to the parser as if it were SQL.
            String arg = args[0].trim();
            if ("-f".equals(arg) || "-c".equals(arg))
                return null;
            return terminate(args[0]);
        }
        if (args.length == 2 && "-c".equals(args[0]))
            return terminate(args[1]);
        if (args.length == 2 && "-f".equals(args[0])) {
            Path script = Path.of(args[1]);
            // Otherwise a missing file surfaces as a bare path, the whole
            // message of NoSuchFileException, which explains nothing.
            if (!Files.isRegularFile(script))
                throw new IllegalArgumentException("cannot read SQL script: " + args[1]);
            return Files.readString(script);
        }
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
        out.println("  ./engine                             print this message");
        out.println("  ./engine -c \"<sql>\"                   run one SQL statement");
        out.println("  ./engine -f <file>                   run a .sql script");
        out.println();
        out.println("A bare \"<sql>\" argument is accepted as shorthand for -c.");
        out.println("ENGINE_JAVA_OPTS=\"-Dengine.maxRowsPerPartition=<n>\" sets the");
        out.println("partition row cap of tables created by that run.");
        out.println("SELECT rows are written to stdout as headerless CSV; logs go to stderr.");
    }

    /** The partition cap for new tables: the system property, or the default. */
    private static int maxRowsPerPartition() {
        String raw = System.getProperty(MAX_ROWS_PER_PARTITION_PROPERTY);
        if (raw == null || raw.isBlank())
            return StorageEngine.DEFAULT_MAX_ROWS_PER_PARTITION;
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "invalid " + MAX_ROWS_PER_PARTITION_PROPERTY + ": " + raw, e);
        }
        if (value <= 0)
            throw new IllegalArgumentException(
                    MAX_ROWS_PER_PARTITION_PROPERTY + " must be > 0: " + raw);
        return value;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
