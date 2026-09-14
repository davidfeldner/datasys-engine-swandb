package dk.itu.swandb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dk.itu.swandb.enums.ColumnType;
import dk.itu.swandb.enums.Comparison;

/**
 * End-to-end tests driving the public {@link StorageEngine} API.
 * Runs under the Failsafe plugin (mvn verify), not Surefire.
 */
class StorageEngineIT {

    private static final List<ColumnSpec> TRIPS_SCHEMA = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    private static Path copyResource(@TempDir Path tmp, String resourceName) throws Exception {
        Path target = tmp.resolve(resourceName);
        try (InputStream in = StorageEngineIT.class.getClassLoader()
                .getResourceAsStream(resourceName)) {
            assertTrue(in != null, "missing test resource " + resourceName);
            Files.write(target, in.readAllBytes());
        }
        return target;
    }

    @Test
    void schemaPersistsAcrossRestart(@TempDir Path tmp) throws Exception {
        StorageEngine a = new StorageEngine(tmp);
        a.createTable("trips", TRIPS_SCHEMA);

        // New engine on the same dir; the table must be reloaded from disk.
        StorageEngine b = new StorageEngine(tmp);
        Path csv = copyResource(tmp, "trips.csv");
        b.copyFile("trips", csv.toString());
        List<Object[]> rows = b.select("trips", "distance", Comparison.GREATER_THAN, 0L);
        assertEquals(8, rows.size());
    }

    @Test
    void duplicateTableThrows(@TempDir Path tmp) {
        StorageEngine engine = new StorageEngine(tmp);
        engine.createTable("trips", TRIPS_SCHEMA);
        assertThrows(IllegalArgumentException.class,
                () -> engine.createTable("trips", TRIPS_SCHEMA));
    }

    @Test
    void roundTrip(@TempDir Path tmp) throws Exception {
        StorageEngine engine = new StorageEngine(tmp);
        engine.createTable("trips", TRIPS_SCHEMA);
        Path csv = copyResource(tmp, "trips.csv");
        engine.copyFile("trips", csv.toString());

        List<Object[]> rows = engine.select("trips", "distance", Comparison.GREATER_THAN, -1L);
        assertEquals(8, rows.size());

        // Verify all rows have the correct Java type and content (order matches CSV).
        Object[][] expected = {
                new Object[]{"Copenhagen", 12L, 23.5},
                new Object[]{"Aarhus", 187L, 301.0},
                new Object[]{"Odense", 95L, 120.75},
                new Object[]{"Copenhagen", 140L, 210.0},
                new Object[]{"Aalborg", 210L, 340.5},
                new Object[]{"Roskilde", 31L, 45.0},
                new Object[]{"Copenhagen", 88L, 99.99},
                new Object[]{"Esbjerg", 299L, 450.25}
        };
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i][0], rows.get(i)[0], "city mismatch at row " + i);
            assertEquals(expected[i][1], rows.get(i)[1], "distance mismatch at row " + i);
            assertEquals(expected[i][2], rows.get(i)[2], "price mismatch at row " + i);
        }
    }

    @Test
    void allComparisonsAllTypes(@TempDir Path tmp) throws Exception {
        StorageEngine engine = new StorageEngine(tmp, 2);
        engine.createTable("trips", TRIPS_SCHEMA);
        Path csv = copyResource(tmp, "trips.csv");
        engine.copyFile("trips", csv.toString());

        // STRING EQUALS
        List<Object[]> eqCity = engine.select("trips", "city", Comparison.EQUALS, "Copenhagen");
        assertEquals(3, eqCity.size());

        // STRING LESS_THAN (any city < "B"): Aarhus, Aalborg
        List<Object[]> ltCity = engine.select("trips", "city", Comparison.LESS_THAN, "B");
        assertEquals(2, ltCity.size());

        // STRING GREATER_THAN (any city > "M")
        List<Object[]> gtCity = engine.select("trips", "city", Comparison.GREATER_THAN, "M");
        // Odense, Roskilde (and Copenhagen starts with C < M -> no), Esbjerg starts with E < M
        // Copenhagen C, Aalborg A, Roskilde R, Esbjerg E, Odense O
        // > "M": O, R => 2 rows
        assertEquals(2, gtCity.size());

        // LONG EQUALS
        List<Object[]> eqLong = engine.select("trips", "distance", Comparison.EQUALS, 95L);
        assertEquals(1, eqLong.size());

        // LONG LESS_THAN
        List<Object[]> ltLong = engine.select("trips", "distance", Comparison.LESS_THAN, 100L);
        assertEquals(4, ltLong.size()); // 12, 95, 31, 88

        // LONG GREATER_THAN
        List<Object[]> gtLong = engine.select("trips", "distance", Comparison.GREATER_THAN, 100L);
        assertEquals(4, gtLong.size()); // 187, 140, 210, 299

        // DOUBLE EQUALS
        List<Object[]> eqDbl = engine.select("trips", "price", Comparison.EQUALS, 99.99);
        assertEquals(1, eqDbl.size());

        // DOUBLE LESS_THAN
        List<Object[]> ltDbl = engine.select("trips", "price", Comparison.LESS_THAN, 50.0);
        assertEquals(2, ltDbl.size()); // 23.5, 45.0

        // DOUBLE GREATER_THAN
        List<Object[]> gtDbl = engine.select("trips", "price", Comparison.GREATER_THAN, 200.0);
        assertEquals(4, gtDbl.size()); // 301.0, 210.0, 340.5, 450.25
    }

    @Test
    void emptyResult(@TempDir Path tmp) throws Exception {
        StorageEngine engine = new StorageEngine(tmp);
        engine.createTable("trips", TRIPS_SCHEMA);
        Path csv = copyResource(tmp, "trips.csv");
        engine.copyFile("trips", csv.toString());

        List<Object[]> rows = engine.select("trips", "city",
                Comparison.EQUALS, "NonExistent");
        assertEquals(0, rows.size());
    }

    @Test
    void errors(@TempDir Path tmp) {
        StorageEngine engine = new StorageEngine(tmp);
        engine.createTable("trips", TRIPS_SCHEMA);

        // unknown table
        assertThrows(IllegalArgumentException.class,
                () -> engine.select("missing", "distance", Comparison.GREATER_THAN, 0L));
        // unknown column
        assertThrows(IllegalArgumentException.class,
                () -> engine.select("trips", "missing", Comparison.GREATER_THAN, 0L));
        // type mismatch
        assertThrows(IllegalArgumentException.class,
                () -> engine.select("trips", "distance", Comparison.GREATER_THAN, "not a long"));
        // integer (not Long) for LONG column
        assertThrows(IllegalArgumentException.class,
                () -> engine.select("trips", "distance", Comparison.GREATER_THAN, 5));
    }

    @Test
    void partitioningAndMinMax(@TempDir Path tmp) throws Exception {
        StorageEngine engine = new StorageEngine(tmp, 2);
        engine.createTable("trips", TRIPS_SCHEMA);
        Path csv = copyResource(tmp, "trips.csv");
        engine.copyFile("trips", csv.toString());

        // 8 rows / 2 per partition = 4 partitions
        Path catalogFile = tmp.resolve(Catalog.CATALOG_FILE);
        String json = Files.readString(catalogFile);
        assertTrue(json.contains("\"rowCount\" : 2"));
        // 4 partitions => 4 distinct indexes
        int count = json.split("\"index\" : ").length - 1;
        assertEquals(4, count);

        // Verify persisted min/max values for each partition by reading the catalog back
        Catalog catalog = new Catalog(tmp);
        Catalog.TableEntry table = catalog.getTable("trips");
        assertEquals(4, table.partitions.size());

        // Partition 0: Copenhagen,12,23.5 / Aarhus,187,301.0
        verifyPartitionMinMax(table.partitions.get(0), "city", "Aarhus", "Copenhagen");
        verifyPartitionMinMax(table.partitions.get(0), "distance", 12L, 187L);
        verifyPartitionMinMax(table.partitions.get(0), "price", 23.5, 301.0);

        // Partition 1: Odense,95,120.75 / Copenhagen,140,210.0
        verifyPartitionMinMax(table.partitions.get(1), "city", "Copenhagen", "Odense");
        verifyPartitionMinMax(table.partitions.get(1), "distance", 95L, 140L);
        verifyPartitionMinMax(table.partitions.get(1), "price", 120.75, 210.0);

        // Partition 2: Aalborg,210,340.5 / Roskilde,31,45.0
        verifyPartitionMinMax(table.partitions.get(2), "city", "Aalborg", "Roskilde");
        verifyPartitionMinMax(table.partitions.get(2), "distance", 31L, 210L);
        verifyPartitionMinMax(table.partitions.get(2), "price", 45.0, 340.5);

        // Partition 3: Copenhagen,88,99.99 / Esbjerg,299,450.25
        verifyPartitionMinMax(table.partitions.get(3), "city", "Copenhagen", "Esbjerg");
        verifyPartitionMinMax(table.partitions.get(3), "distance", 88L, 299L);
        verifyPartitionMinMax(table.partitions.get(3), "price", 99.99, 450.25);
    }

    private static void verifyPartitionMinMax(Catalog.PartitionEntry partition, String columnName, Object expectedMin, Object expectedMax) {
        Catalog.ColumnSummary summary = partition.columns.stream()
                .filter(cs -> cs.name().equals(columnName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing column summary for " + columnName));
        assertEquals(expectedMin, summary.min(), "min mismatch for " + columnName + " in partition " + partition.index());
        assertEquals(expectedMax, summary.max(), "max mismatch for " + columnName + " in partition " + partition.index());
    }

    @Test
    void pruningObservable(@TempDir Path tmp) throws Exception {
        StorageEngine engine = new StorageEngine(tmp, 2);
        engine.createTable("trips", TRIPS_SCHEMA);
        Path csv = copyResource(tmp, "trips_sorted_by_distance.csv");
        engine.copyFile("trips", csv.toString());

        List<Object[]> rows = engine.select("trips", "distance", Comparison.GREATER_THAN, 200L);
        ScanStats stats = engine.lastScanStats();

        assertTrue(stats.partitionsPruned() >= 2,
                "expected at least 2 pruned partitions, got " + stats.partitionsPruned());
        assertEquals(2, rows.size()); // Aarhus 187 -> no, Aalborg 210, Esbjerg 299
        // Verify the surviving rows by distance value
        List<Long> distances = new ArrayList<>();
        for (Object[] row : rows) {
            distances.add((Long) row[1]);
        }
        assertTrue(distances.contains(210L));
        assertTrue(distances.contains(299L));
    }

    /**
     * Guards the actual I/O pruning: a partition classified as PRUNED must
     * never be decoded. The catalog records every partition's byte offset in
     * the {@code .swan} file; corrupting the column-length prefix of the
     * partitions that {@code distance > 200} prunes makes any attempt to read
     * them fail, so this test passes only while {@code select} seeks straight
     * to the live partition instead of scanning the whole file.
     */
    @Test
    void prunedPartitionsAreNeverReadFromDisk(@TempDir Path tmp) throws Exception {
        StorageEngine engine = new StorageEngine(tmp, 2);
        engine.createTable("trips", TRIPS_SCHEMA);
        Path csv = copyResource(tmp, "trips_sorted_by_distance.csv");
        engine.copyFile("trips", csv.toString());

        // Partitions 0-2 hold distances [12,31], [88,95], [140,187]. Overwrite
        // the length prefix of their first column with -1 so decoding throws.
        Catalog.TableEntry table = new Catalog(tmp).getTable("trips");
        try (RandomAccessFile raf = new RandomAccessFile(tmp.resolve(table.dataFile).toFile(), "rw")) {
            for (int i = 0; i < 3; i++) {
                raf.seek(table.partitions.get(i).offset() + Integer.BYTES); // past rowCount
                raf.writeInt(-1);
            }
        }

        // A fresh engine reloads the same catalog. distance > 200 keeps only
        // partition 3 (210, 299); reading a pruned partition would now blow up.
        StorageEngine reopened = new StorageEngine(tmp, 2);
        List<Object[]> rows = reopened.select("trips", "distance", Comparison.GREATER_THAN, 200L);
        ScanStats stats = reopened.lastScanStats();

        assertEquals(3, stats.partitionsPruned());
        assertEquals(1, stats.partitionsRead());
        List<Long> distances = new ArrayList<>();
        for (Object[] row : rows) {
            distances.add((Long) row[1]);
        }
        assertEquals(List.of(210L, 299L), distances);
    }

    /**
     * A catalog written before offsets were tracked has no {@code offset}
     * field; it must still answer selects by falling back to a sequential
     * read instead of seeking to offset 0.
     */
    @Test
    void catalogWithoutOffsetsFallsBackToSequentialRead(@TempDir Path tmp) throws Exception {
        StorageEngine engine = new StorageEngine(tmp, 2);
        engine.createTable("trips", TRIPS_SCHEMA);
        Path csv = copyResource(tmp, "trips.csv");
        engine.copyFile("trips", csv.toString());

        // Strip every "offset" field from the catalog, as an older writer would.
        Path catalogFile = tmp.resolve(Catalog.CATALOG_FILE);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(catalogFile.toFile());
        for (JsonNode tableNode : root.get("tables")) {
            for (JsonNode partition : tableNode.get("partitions")) {
                ((ObjectNode) partition).remove("offset");
            }
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(catalogFile.toFile(), root);

        StorageEngine reopened = new StorageEngine(tmp, 2);
        assertEquals(-1, new Catalog(tmp).getTable("trips").partitions.get(0).offset());

        List<Object[]> rows = reopened.select("trips", "distance", Comparison.GREATER_THAN, 100L);
        assertEquals(4, rows.size()); // 187, 140, 210, 299
    }

    @Test
    void dataPersistenceAcrossRestart(@TempDir Path tmp) throws Exception {
        Path csv = copyResource(tmp, "trips.csv");

        StorageEngine a = new StorageEngine(tmp, 4);
        a.createTable("trips", TRIPS_SCHEMA);
        a.copyFile("trips", csv.toString());

        List<Object[]> fromA = a.select("trips", "distance", Comparison.GREATER_THAN, -1L);

        StorageEngine b = new StorageEngine(tmp, 4);
        List<Object[]> fromB = b.select("trips", "distance", Comparison.GREATER_THAN, -1L);

        assertEquals(fromA.size(), fromB.size());
        for (int i = 0; i < fromA.size(); i++) {
            assertEquals(fromA.get(i)[0], fromB.get(i)[0]);
            assertEquals(fromA.get(i)[1], fromB.get(i)[1]);
            assertEquals(fromA.get(i)[2], fromB.get(i)[2]);
        }
    }
}
