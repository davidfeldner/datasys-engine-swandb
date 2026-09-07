package dk.itu.swandb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

        // Verify each value has the correct Java type and content.
        Object[] first = rows.get(0);
        assertEquals("Copenhagen", first[0]);
        assertEquals(12L, first[1]);
        assertEquals(23.5, first[2]);
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
