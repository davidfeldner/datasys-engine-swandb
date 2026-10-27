package dk.itu.swandb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.swandb.enums.ColumnType;
import dk.itu.swandb.enums.Comparison;
import dk.itu.swandb.plan.Selection;

/**
 * Exercises 7's versioning requirements against the real engine: versions
 * 0..n through CREATE/COPY/COPY/DELETE, a SELECT after each commit, the
 * DELETE rewrite set over the sorted golden data, and what a fresh engine
 * sees after a restart. Runs under the Failsafe plugin (mvn verify).
 */
class VersionedTableIT {

    private static final List<ColumnSpec> TRIPS_SCHEMA = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    // --- 3. versions ---------------------------------------------------

    @Test
    void createCopyCopyDeleteProduceVersionsZeroToThree(@TempDir Path tmp) throws Exception {
        Path csv = copyResource(tmp, "trips.csv");
        StorageEngine engine = new StorageEngine(tmp, 8);

        assertEquals(0, engine.createTable("trips", TRIPS_SCHEMA).version(), "CREATE is version 0");
        assertEquals(0, engine.version("trips"));
        assertEquals(0, engine.selectAll("trips").size(), "version 0 is the empty table");

        Result firstCopy = engine.copyFile("trips", csv.toString());
        assertEquals(1, firstCopy.version());
        assertEquals(8, firstCopy.rows());
        assertEquals(1, firstCopy.partitions(), "8 rows with maxRowsPerPartition 8 is one partition");
        assertEquals(8, engine.selectAll("trips").size());

        // A second COPY appends to the same table and commits the next version.
        Result secondCopy = engine.copyFile("trips", csv.toString());
        assertEquals(2, secondCopy.version());
        assertEquals(1, secondCopy.partitions(), "the second COPY appends its own partition");
        assertEquals(16, engine.selectAll("trips").size(), "version 2 holds both loads");

        Result delete = engine.delete("trips",
                Optional.of(new Selection("city", Comparison.EQUALS, "Copenhagen")));
        assertEquals(3, delete.version());
        assertEquals(6, delete.rows(), "three Copenhagen rows in each of the two loads");
        assertEquals(10, engine.selectAll("trips").size(), "16 rows minus the 6 Copenhagen rows");

        // Every committed version is still live for whoever pinned it.
        assertEquals(List.of(0, 1, 2, 3), engine.liveVersions("trips"));
        assertEquals(3, engine.version("trips"), "the newest version is what a statement reads");
    }

    @Test
    void aSelectSeesEachCommitInTurn(@TempDir Path tmp) throws Exception {
        Path csv = copyResource(tmp, "trips.csv");
        StorageEngine engine = new StorageEngine(tmp, 8);
        engine.createTable("trips", TRIPS_SCHEMA);

        List<Integer> sizes = new ArrayList<>();
        List<Integer> versions = new ArrayList<>();
        sizes.add(engine.selectAll("trips").size());
        versions.add(engine.version("trips"));

        engine.copyFile("trips", csv.toString());
        sizes.add(engine.selectAll("trips").size());
        versions.add(engine.version("trips"));

        engine.copyFile("trips", csv.toString());
        sizes.add(engine.selectAll("trips").size());
        versions.add(engine.version("trips"));

        engine.delete("trips", Optional.of(new Selection("distance", Comparison.GREATER_THAN, 200L)));
        sizes.add(engine.selectAll("trips").size());
        versions.add(engine.version("trips"));

        assertEquals(List.of(0, 1, 2, 3), versions);
        // 0 rows, then 8, then 16, then 16 minus the four rows above 200.
        assertEquals(List.of(0, 8, 16, 12), sizes);
    }

    @Test
    void aSelectBetweenCommitsReadsTheVersionItPinned(@TempDir Path tmp) throws Exception {
        Path csv = copyResource(tmp, "trips.csv");
        StorageEngine engine = new StorageEngine(tmp, 8);
        engine.createTable("trips", TRIPS_SCHEMA);
        engine.copyFile("trips", csv.toString());
        engine.copyFile("trips", csv.toString());

        // The executor reports the version a SELECT read; the same figure is
        // exposed for the API, and it is the version the rows came from.
        List<Object[]> rows = engine.select("trips", "city", Comparison.EQUALS, "Copenhagen");
        assertEquals(2, engine.lastReadVersion());
        assertEquals(6, rows.size());

        engine.delete("trips", Optional.of(new Selection("city", Comparison.EQUALS, "Copenhagen")));
        engine.selectAll("trips");
        assertEquals(3, engine.lastReadVersion());
    }

    @Test
    void aDeleteThatMatchesNothingDoesNotCreateAVersion(@TempDir Path tmp) throws Exception {
        Path csv = copyResource(tmp, "trips.csv");
        StorageEngine engine = new StorageEngine(tmp, 8);
        engine.createTable("trips", TRIPS_SCHEMA);
        engine.copyFile("trips", csv.toString());

        Result nothing = engine.delete("trips",
                Optional.of(new Selection("city", Comparison.EQUALS, "NoSuchPlace")));

        assertEquals(0, nothing.rows());
        assertEquals(1, nothing.version(), "no match: the table stays at version 1");
        assertEquals(1, engine.version("trips"));
        assertEquals(8, engine.selectAll("trips").size());
    }

    // --- 4. the rewrite set --------------------------------------------

    /**
     * Sorted golden data, maxRowsPerPartition = 2, so the four partitions are
     * [12,31], [88,95], [140,187], [210,299]. Deleting {@code distance > 200}
     * can only match partition 3, so exactly one partition is rewritten and
     * the other three keep the offsets they were written with.
     */
    @Test
    void deleteRewritesExactlyThePartitionsThatCanMatch(@TempDir Path tmp) throws Exception {
        Path csv = copyResource(tmp, "trips_sorted_by_distance.csv");
        StorageEngine engine = new StorageEngine(tmp, 2);
        engine.createTable("trips", TRIPS_SCHEMA);
        engine.copyFile("trips", csv.toString());
        engine.close();

        List<Long> offsetsBefore = offsets(tmp);

        StorageEngine reopened = new StorageEngine(tmp, 2);
        Result delete = reopened.delete("trips",
                Optional.of(new Selection("distance", Comparison.GREATER_THAN, 200L)));

        assertEquals(2, delete.rows(), "210 and 299");
        assertEquals(1, delete.partitionsRewritten(), "only [210,299] can match");
        assertEquals(3, delete.partitionsCarried());
        assertEquals(2, delete.version());

        reopened.close();
        List<Long> offsetsAfter = offsets(tmp);
        assertEquals(3, offsetsAfter.size(), "the emptied partition leaves the table");
        for (int i = 0; i < 3; i++) {
            assertEquals(offsetsBefore.get(i), offsetsAfter.get(i),
                    "carried partition " + i + " must keep its offset");
        }

        StorageEngine check = new StorageEngine(tmp, 2);
        assertEquals(List.of(), check.select("trips", "distance", Comparison.GREATER_THAN, 200L));
        assertEquals(6, check.selectAll("trips").size());
    }

    /**
     * The mirror image of the pruning test: a predicate that prunes three
     * partitions for SELECT rewrites exactly those three for DELETE.
     */
    @Test
    void theRewriteSetIsTheComplementOfThePruningSet(@TempDir Path tmp) throws Exception {
        Path csv = copyResource(tmp, "trips_sorted_by_distance.csv");
        StorageEngine engine = new StorageEngine(tmp, 2);
        engine.createTable("trips", TRIPS_SCHEMA);
        engine.copyFile("trips", csv.toString());

        Selection selection = new Selection("distance", Comparison.LESS_THAN, 100L);
        engine.select("trips", Optional.of(selection));
        int pruned = engine.lastPartitionsPruned();

        Result delete = engine.delete("trips", Optional.of(selection));

        assertEquals(4 - pruned, delete.partitionsRewritten(),
                "DELETE rewrites the partitions SELECT reads: " + delete);
        assertEquals(pruned, delete.partitionsCarried());
    }

    // --- 5. restart ----------------------------------------------------

    @Test
    void aFreshEngineSeesThePostDeleteVersion(@TempDir Path tmp) throws Exception {
        Path csv = copyResource(tmp, "trips.csv");
        StorageEngine engine = new StorageEngine(tmp, 8);
        engine.createTable("trips", TRIPS_SCHEMA);
        engine.copyFile("trips", csv.toString());
        engine.copyFile("trips", csv.toString());
        engine.delete("trips", Optional.of(new Selection("city", Comparison.EQUALS, "Copenhagen")));
        // The catalog is written when the engine closes.
        engine.close();

        StorageEngine fresh = new StorageEngine(tmp, 8);

        assertEquals(3, fresh.version("trips"), "the fresh engine restarts at the committed version");
        assertEquals(10, fresh.selectAll("trips").size());
        assertEquals(List.of(), fresh.select("trips", "city", Comparison.EQUALS, "Copenhagen"));
    }

    @Test
    void theCarriedPartitionsSurviveARestartUnchanged(@TempDir Path tmp) throws Exception {
        Path csv = copyResource(tmp, "trips_sorted_by_distance.csv");
        StorageEngine engine = new StorageEngine(tmp, 2);
        engine.createTable("trips", TRIPS_SCHEMA);
        engine.copyFile("trips", csv.toString());
        engine.delete("trips", Optional.of(new Selection("distance", Comparison.GREATER_THAN, 200L)));
        engine.close();

        StorageEngine fresh = new StorageEngine(tmp, 2);
        List<Object[]> rows = fresh.selectAll("trips");
        assertEquals(6, rows.size());
        assertEquals("Copenhagen", rows.get(0)[0]);
        assertEquals(12L, rows.get(0)[1]);
        assertEquals("Aarhus", rows.get(5)[0]);
        assertEquals(187L, rows.get(5)[1]);
    }

    // --- retention -----------------------------------------------------

    @Test
    void versionsBeyondTheRetentionWindowAreDroppedAndSpaceReclaimed(@TempDir Path tmp) throws Exception {
        Path csv = copyResource(tmp, "trips.csv");
        StorageEngine engine = new StorageEngine(tmp, 8);
        engine.createTable("trips", TRIPS_SCHEMA);
        engine.copyFile("trips", csv.toString());
        Path data = tmp.resolve("trips.swan");
        long afterOneCopy = Files.size(data);

        // Each commit appends a partition and truncates the ones the
        // retention window no longer needs.
        for (int i = 0; i < VersionedTable.RETAINED_VERSIONS; i++) {
            engine.copyFile("trips", csv.toString());
        }

        // Every version from 0..RETAINED_VERSIONS is still live, so the file
        // holds each written partition: it grew rather than being overwritten.
        assertTrue(Files.size(data) > afterOneCopy,
                "retained versions keep their partitions in the file");
        List<Integer> live = engine.liveVersions("trips");
        assertEquals(VersionedTable.RETAINED_VERSIONS, live.size(),
                "exactly the newest RETAINED_VERSIONS versions are kept: " + live);
        assertEquals(1 + VersionedTable.RETAINED_VERSIONS, engine.version("trips"));
        assertEquals(8 * (VersionedTable.RETAINED_VERSIONS + 1), engine.selectAll("trips").size());
    }

    // --- helpers -------------------------------------------------------

    private static List<Long> offsets(Path dir) {
        Catalog.TableEntry table = new Catalog(dir).getTable("trips");
        List<Long> offsets = new ArrayList<>();
        for (Catalog.PartitionEntry partition : table.partitions) offsets.add(partition.offset());
        return offsets;
    }

    private static Path copyResource(Path tmp, String resourceName) throws Exception {
        Path target = tmp.resolve(resourceName);
        try (InputStream in = VersionedTableIT.class.getClassLoader()
                .getResourceAsStream(resourceName)) {
            assertNotNull(in, "missing test resource " + resourceName);
            Files.write(target, in.readAllBytes());
        }
        return target;
    }
}
