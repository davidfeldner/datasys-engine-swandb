package dk.itu.swandb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.swandb.enums.ColumnType;
import dk.itu.swandb.enums.Comparison;
import dk.itu.swandb.plan.Operator;
import dk.itu.swandb.plan.Selection;

/**
 * Exercise 8's required concurrency tests: snapshot reads without threads,
 * and two writers racing for one table with the conflict detected at commit.
 *
 * <p>The interleaving is forced, not hoped for: a writer parks in
 * {@link StorageEngine#beforeCommit} on a latch until the winner has
 * committed, so the tests are deterministic and never sleep. Every await
 * carries a timeout, so a broken engine fails the test instead of hanging CI.
 * Runs under the Failsafe plugin ({@code mvn verify}).
 */
class ConflictIT {

    private static final List<ColumnSpec> TRIPS_SCHEMA = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    private static final Selection COPENHAGEN =
            new Selection("city", Comparison.EQUALS, "Copenhagen");

    // --- 1. snapshot read, no threads ----------------------------------

    /**
     * Required test 1: open a SELECT's plan, run a whole DELETE from the same
     * thread, then drain the scan. The rows are the pinned version's, because
     * the scan carries its snapshot's partitions with their offsets and
     * counts, not indices into the catalog the DELETE just moved.
     */
    @Test
    void aScanDrainsTheVersionItPinnedEvenAfterADeleteCommits(@TempDir Path tmp) throws Exception {
        Path csv = copyResource(tmp, "trips.csv");
        StorageEngine engine = new StorageEngine(tmp, 2);
        engine.createTable("trips", TRIPS_SCHEMA);
        engine.copyFile("trips", csv.toString());
        List<Object[]> pinnedRows = engine.selectAll("trips");
        assertEquals(8, pinnedRows.size());

        try (PinnedQuery query = engine.openQuery("trips", Optional.empty())) {
            assertEquals(1, query.version());
            Operator scan = query.root();
            scan.open();
            // Read the first partition before the DELETE runs; the rest of the
            // scan is decoded after the commit, from the pinned offsets.
            List<Object[]> rows = new ArrayList<>();
            rows.add(scan.next());
            rows.add(scan.next());

            Result delete = engine.delete("trips", Optional.of(COPENHAGEN));
            assertEquals(2, delete.version(), "the DELETE commits version 2");
            assertEquals(5, engine.selectAll("trips").size(), "the committed table lost three rows");

            Object[] row;
            while ((row = scan.next()) != null) rows.add(row);
            scan.close();

            assertRows(pinnedRows, rows);
        }
    }

    // --- 2. DELETE parked, COPY commits, DELETE aborts -----------------

    /**
     * Required test 2: the two-writer choreography. The DELETE pins version 1
     * and parks right before validation; the COPY commits version 2; the
     * DELETE resumes on a moved base and aborts with {@link ConflictException}.
     */
    @Test
    void aDeleteParkedBeforeCommitAbortsWhenACopyCommitsFirst(@TempDir Path tmp) throws Exception {
        String table = taggedTable();
        Path csv = copyResource(tmp, "trips.csv");
        StorageEngine engine = new StorageEngine(tmp, 2);
        engine.createTable(table, TRIPS_SCHEMA);
        engine.copyFile(table, csv.toString());

        ExecutionException failure = raceDeleteAgainstCopy(engine, csv, table);

        assertInstanceOf(ConflictException.class, failure.getCause());
        assertEquals("conflict table=" + table + " base=1 current=2",
                failure.getCause().getMessage());
        assertEquals(16, engine.selectAll(table).size(),
                "the winner's two loads are all that is visible");
        assertEquals(List.of(0, 1, 2), engine.liveVersions(table),
                "only the winner's version 2 was committed; the loser added none");

        // The log tells the same story: the winner committed version 2 and the
        // loser left an ERROR line, each on its own thread id.
        List<String[]> log = versionedTableLog(table);
        String[] winner = log.stream()
                .filter(fields -> fields[4].equals("DEBUG") && fields[6].contains("version=2"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no commit line for version 2 of " + table + ": " + log));
        String[] loser = log.stream()
                .filter(fields -> fields[4].equals("ERROR"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no ERROR line for the conflict on " + table + ": " + log));
        assertTrue(loser[6].contains("conflict table=" + table + " base=1 current=2"), loser[6]);
        assertNotEquals(winner[3], loser[3], "two writers must appear with two thread ids");
    }

    // --- 3. COPY parked, COPY commits, parked COPY aborts --------------

    /** Required test 3: the same race with two COPY statements. */
    @Test
    void aCopyParkedBeforeCommitAbortsWhenAnotherCopyCommitsFirst(@TempDir Path tmp) throws Exception {
        String table = taggedTable();
        Path csv = copyResource(tmp, "trips.csv");
        StorageEngine engine = new StorageEngine(tmp, 2);
        engine.createTable(table, TRIPS_SCHEMA);
        engine.copyFile(table, csv.toString());

        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch winnerDone = new CountDownLatch(1);
        engine.beforeCommit = () -> {
            parked.countDown();
            await(winnerDone);
        };

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> loser = executor.submit(() -> engine.copyFile(table, csv.toString()));
            assertTrue(parked.await(5, TimeUnit.MINUTES),
                    "the second COPY should park before its commit");
            engine.beforeCommit = () -> { };
            engine.copyFile(table, csv.toString());
            assertEquals(2, engine.version(table), "the winner commits version 2");
            winnerDone.countDown();

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> loser.get(5, TimeUnit.MINUTES));
            assertInstanceOf(ConflictException.class, failure.getCause());
        } finally {
            winnerDone.countDown();
            executor.shutdownNow();
        }

        assertEquals(2, engine.version(table));
        assertEquals(16, engine.selectAll(table).size(), "only the winner's copy is visible");
    }

    // --- 4. a clean failure can be retried -----------------------------

    /**
     * Required test 4: after the abort the table equals exactly the winner's
     * result, and rerunning the loser's statement on the fresh base succeeds.
     */
    @Test
    void afterAnAbortTheWinnerIsVisibleAndTheLoserCanBeRetried(@TempDir Path tmp) throws Exception {
        String table = taggedTable();
        Path csv = copyResource(tmp, "trips.csv");
        StorageEngine engine = new StorageEngine(tmp, 2);
        engine.createTable(table, TRIPS_SCHEMA);
        engine.copyFile(table, csv.toString());
        List<Object[]> firstLoad = engine.selectAll(table);

        raceDeleteAgainstCopy(engine, csv, table);

        assertEquals(2, engine.version(table), "the winner's version is the committed one");
        // Exactly the winner's result: the first load followed by the second.
        List<Object[]> winner = new ArrayList<>(firstLoad);
        winner.addAll(firstLoad);
        assertRows(winner, engine.selectAll(table));
        assertEquals(6L, winner.stream().filter(row -> "Copenhagen".equals(row[0])).count());

        Result retried = engine.delete(table, Optional.of(COPENHAGEN));
        assertEquals(3, retried.version(), "the retry commits on the winner's base");
        assertEquals(6, retried.rows(), "three Copenhagen rows in each of the two loads");
        assertEquals(10, engine.selectAll(table).size());
        assertEquals(0, engine.select(table, "city", Comparison.EQUALS, "Copenhagen").size());
    }

    // --- helpers -------------------------------------------------------

    /**
     * Runs the deterministic DELETE-versus-COPY interleaving of Exercise 8 and
     * returns after the DELETE has failed. A unique table name per test keeps
     * the log assertions clear of every other run.
     */
    private static ExecutionException raceDeleteAgainstCopy(StorageEngine engine, Path csv, String table)
            throws Exception {
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch winnerDone = new CountDownLatch(1);
        engine.beforeCommit = () -> {
            parked.countDown();
            await(winnerDone);
        };

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> loser = executor.submit(() -> engine.delete(table, Optional.of(COPENHAGEN)));
            assertTrue(parked.await(5, TimeUnit.MINUTES), "the DELETE should park before its commit");
            // The parked writer is already inside the old hook, so resetting
            // the field here only keeps the winner from parking too.
            engine.beforeCommit = () -> { };
            engine.copyFile(table, csv.toString());
            assertEquals(2, engine.version(table), "the COPY commits version 2");
            winnerDone.countDown();

            return assertThrows(ExecutionException.class, () -> loser.get(5, TimeUnit.MINUTES));
        } finally {
            winnerDone.countDown();
            executor.shutdownNow();
        }
    }

    /** A bounded wait: a latch bug fails the test instead of hanging CI. */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.MINUTES))
                throw new AssertionError("timed out after 5 minutes waiting for a latch");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while parked in beforeCommit", e);
        }
    }

    /** The CSV log's seven fields for this table, written by {@code VersionedTable}. */
    private static List<String[]> versionedTableLog(String table) throws Exception {
        return Files.readAllLines(Path.of("logs", "engine.log")).stream()
                .filter(line -> line.contains(table))
                .map(line -> line.split(",", -1))
                .filter(fields -> fields.length == 7 && fields[5].equals("VersionedTable"))
                .toList();
    }

    private static String taggedTable() {
        return "trips" + UUID.randomUUID().toString().replace("-", "");
    }

    private static Path copyResource(Path tmp, String resourceName) throws Exception {
        Path target = tmp.resolve(resourceName);
        try (InputStream in = ConflictIT.class.getClassLoader().getResourceAsStream(resourceName)) {
            assertTrue(in != null, "missing test resource " + resourceName);
            Files.write(target, in.readAllBytes());
        }
        return target;
    }

    private static void assertRows(List<Object[]> expected, List<Object[]> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertArrayEquals(expected.get(i), actual.get(i), "row " + i);
        }
    }
}
