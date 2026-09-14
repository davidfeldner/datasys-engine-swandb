package dk.itu.swandb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end tests driving the public {@link StorageEngine} API.
 * Runs under the Failsafe plugin (mvn verify), not Surefire.
 *
 * <p>Assertions compare against the golden data in full — every row, every
 * value, and the Java type of each value — rather than row counts alone.
 */
class StorageEngineIT {

    private static final List<ColumnSpec> TRIPS_SCHEMA = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    /** The golden file {@code trips.csv}, in on-disk (file) order. */
    private static final List<Object[]> GOLDEN = List.<Object[]>of(
            new Object[]{"Copenhagen", 12L, 23.5},
            new Object[]{"Aarhus", 187L, 301.0},
            new Object[]{"Odense", 95L, 120.75},
            new Object[]{"Copenhagen", 140L, 210.0},
            new Object[]{"Aalborg", 210L, 340.5},
            new Object[]{"Roskilde", 31L, 45.0},
            new Object[]{"Copenhagen", 88L, 99.99},
            new Object[]{"Esbjerg", 299L, 450.25});

    /** The golden file sorted by distance, in on-disk (file) order. */
    private static final List<Object[]> GOLDEN_BY_DISTANCE = List.<Object[]>of(
            new Object[]{"Copenhagen", 12L, 23.5},
            new Object[]{"Roskilde", 31L, 45.0},
            new Object[]{"Copenhagen", 88L, 99.99},
            new Object[]{"Odense", 95L, 120.75},
            new Object[]{"Copenhagen", 140L, 210.0},
            new Object[]{"Aarhus", 187L, 301.0},
            new Object[]{"Aalborg", 210L, 340.5},
            new Object[]{"Esbjerg", 299L, 450.25});

    private static Path copyResource(@TempDir Path tmp, String resourceName) throws Exception {
        Path target = tmp.resolve(resourceName);
        try (InputStream in = StorageEngineIT.class.getClassLoader()
                .getResourceAsStream(resourceName)) {
            assertTrue(in != null, "missing test resource " + resourceName);
            Files.write(target, in.readAllBytes());
        }
        return target;
    }

    /** Assert the returned rows match {@code expected} exactly, in order, by value and type. */
    private static void assertRowsEqual(List<Object[]> expected, List<Object[]> actual) {
        assertEquals(expected.size(), actual.size(), "row count");
        for (int i = 0; i < expected.size(); i++) {
            assertArrayEquals(expected.get(i), actual.get(i), "row " + i);
        }
    }

    private static Catalog.ColumnSummary summary(Catalog.TableEntry table, int partition, String column) {
        for (Catalog.ColumnSummary s : table.partitions.get(partition).columns) {
            if (s.name().equals(column)) return s;
        }
        throw new AssertionError("no summary for column " + column + " in partition " + partition);
    }

    @Test
    void schemaPersistsAcrossRestart(@TempDir Path tmp) throws Exception {
        StorageEngine a = new StorageEngine(tmp);
        a.createTable("trips", TRIPS_SCHEMA);

        // New engine on the same dir; the table must be reloaded from disk.
        StorageEngine b = new StorageEngine(tmp);
        Path csv = copyResource(tmp, "trips.csv");
        b.copyFile("trips", csv.toString());

        assertRowsEqual(GOLDEN,
                b.select("trips", "distance", Comparison.GREATER_THAN, -1L));
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

        // A predicate matching everything must return all eight rows with
        // the right values, in schema column order, in on-disk order.
        List<Object[]> rows = engine.select("trips", "distance", Comparison.GREATER_THAN, -1L);
        assertRowsEqual(GOLDEN, rows);

        // Spot-check the declared Java types of a returned row.
        Object[] row = rows.get(0);
        assertTrue(row[0] instanceof String, "city must be a String");
        assertTrue(row[1] instanceof Long, "distance must be a Long");
        assertTrue(row[2] instanceof Double, "price must be a Double");
    }

    @Test
    void allComparisonsAllTypes(@TempDir Path tmp) throws Exception {
        StorageEngine engine = new StorageEngine(tmp, 2);
        engine.createTable("trips", TRIPS_SCHEMA);
        Path csv = copyResource(tmp, "trips.csv");
        engine.copyFile("trips", csv.toString());

        // --- STRING: lexicographic ASCII -------------------------------------
        assertRowsEqual(
                List.<Object[]>of(GOLDEN.get(0), GOLDEN.get(3), GOLDEN.get(6)),
                engine.select("trips", "city", Comparison.EQUALS, "Copenhagen"));

        // city < "B" -> the two "A..." cities: Aarhus, Aalborg
        assertRowsEqual(
                List.<Object[]>of(GOLDEN.get(1), GOLDEN.get(4)),
                engine.select("trips", "city", Comparison.LESS_THAN, "B"));

        // city > "M" -> Odense, Roskilde
        assertRowsEqual(
                List.<Object[]>of(GOLDEN.get(2), GOLDEN.get(5)),
                engine.select("trips", "city", Comparison.GREATER_THAN, "M"));

        // --- LONG: numeric ---------------------------------------------------
        assertRowsEqual(
                List.<Object[]>of(GOLDEN.get(2)),
                engine.select("trips", "distance", Comparison.EQUALS, 95L));

        // distance < 100 -> 12, 95, 31, 88
        assertRowsEqual(
                List.<Object[]>of(GOLDEN.get(0), GOLDEN.get(2), GOLDEN.get(5), GOLDEN.get(6)),
                engine.select("trips", "distance", Comparison.LESS_THAN, 100L));

        // distance > 100 -> 187, 140, 210, 299
        assertRowsEqual(
                List.<Object[]>of(GOLDEN.get(1), GOLDEN.get(3), GOLDEN.get(4), GOLDEN.get(7)),
                engine.select("trips", "distance", Comparison.GREATER_THAN, 100L));

        // --- DOUBLE: numeric -------------------------------------------------
        assertRowsEqual(
                List.<Object[]>of(GOLDEN.get(6)),
                engine.select("trips", "price", Comparison.EQUALS, 99.99));

        // price < 50.0 -> 23.5, 45.0
        assertRowsEqual(
                List.<Object[]>of(GOLDEN.get(0), GOLDEN.get(5)),
                engine.select("trips", "price", Comparison.LESS_THAN, 50.0));

        // price > 200.0 -> 301.0, 210.0, 340.5, 450.25
        assertRowsEqual(
                List.<Object[]>of(GOLDEN.get(1), GOLDEN.get(3), GOLDEN.get(4), GOLDEN.get(7)),
                engine.select("trips", "price", Comparison.GREATER_THAN, 200.0));
    }

    @Test
    void stringValuesPreserveWhitespace(@TempDir Path tmp) throws Exception {
        StorageEngine engine = new StorageEngine(tmp);
        engine.createTable("trips", TRIPS_SCHEMA);
        Path csv = copyResource(tmp, "trips_spaces.csv");
        engine.copyFile("trips", csv.toString());

        // Row 0's city has a trailing space; it must survive the CSV read,
        // the binary write, and the read back out, unchanged.
        List<Object[]> all = engine.select("trips", "distance", Comparison.GREATER_THAN, -1L);
        assertRowsEqual(
                List.<Object[]>of(
                        new Object[]{"Copenhagen ", 12L, 23.5},
                        new Object[]{"Aarhus", 187L, 301.0},
                        new Object[]{"Copenhagen", 88L, 99.99}),
                all);

        // Equality must distinguish the padded value from the unpadded one.
        assertRowsEqual(
                List.<Object[]>of(new Object[]{"Copenhagen", 88L, 99.99}),
                engine.select("trips", "city", Comparison.EQUALS, "Copenhagen"));
        assertRowsEqual(
                List.<Object[]>of(new Object[]{"Copenhagen ", 12L, 23.5}),
                engine.select("trips", "city", Comparison.EQUALS, "Copenhagen "));

        // Ordering: "Copenhagen" < "Copenhagen " because the padded value is
        // the longer string with an identical prefix.
        assertRowsEqual(
                List.<Object[]>of(new Object[]{"Aarhus", 187L, 301.0}),
                engine.select("trips", "city", Comparison.LESS_THAN, "Copenhagen"));
        assertRowsEqual(
                List.<Object[]>of(new Object[]{"Copenhagen ", 12L, 23.5}),
                engine.select("trips", "city", Comparison.GREATER_THAN, "Copenhagen"));

        // The persisted min/max must reflect the preserved value too.
        Catalog.TableEntry table = new Catalog(tmp).getTable("trips");
        assertEquals("Aarhus", summary(table, 0, "city").min());
        assertEquals("Copenhagen ", summary(table, 0, "city").max());
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

        // Read the persisted summaries back from wherever they are stored
        // (the catalog) via a fresh engine, so this exercises restart too.
        Catalog.TableEntry table = new Catalog(tmp).getTable("trips");
        assertEquals(4, table.partitions.size());
        for (int p = 0; p < 4; p++) {
            assertEquals(p, table.partitions.get(p).index(), "partition index " + p);
            assertEquals(2, table.partitions.get(p).rowCount(), "partition " + p + " rowCount");
        }

        // Partition 0 holds golden rows 0-1: (Copenhagen,12,23.5),(Aarhus,187,301.0)
        assertEquals("Aarhus", summary(table, 0, "city").min());
        assertEquals("Copenhagen", summary(table, 0, "city").max());
        assertEquals(12L, summary(table, 0, "distance").min());
        assertEquals(187L, summary(table, 0, "distance").max());
        assertEquals(23.5, summary(table, 0, "price").min());
        assertEquals(301.0, summary(table, 0, "price").max());

        // Partition 1 holds golden rows 2-3: (Odense,95,120.75),(Copenhagen,140,210.0)
        assertEquals("Copenhagen", summary(table, 1, "city").min());
        assertEquals("Odense", summary(table, 1, "city").max());
        assertEquals(95L, summary(table, 1, "distance").min());
        assertEquals(140L, summary(table, 1, "distance").max());
        assertEquals(120.75, summary(table, 1, "price").min());
        assertEquals(210.0, summary(table, 1, "price").max());

        // Partition 2 holds golden rows 4-5: (Aalborg,210,340.5),(Roskilde,31,45.0)
        assertEquals("Aalborg", summary(table, 2, "city").min());
        assertEquals("Roskilde", summary(table, 2, "city").max());
        assertEquals(31L, summary(table, 2, "distance").min());
        assertEquals(210L, summary(table, 2, "distance").max());
        assertEquals(45.0, summary(table, 2, "price").min());
        assertEquals(340.5, summary(table, 2, "price").max());

        // Partition 3 holds golden rows 6-7: (Copenhagen,88,99.99),(Esbjerg,299,450.25)
        assertEquals("Copenhagen", summary(table, 3, "city").min());
        assertEquals("Esbjerg", summary(table, 3, "city").max());
        assertEquals(88L, summary(table, 3, "distance").min());
        assertEquals(299L, summary(table, 3, "distance").max());
        assertEquals(99.99, summary(table, 3, "price").min());
        assertEquals(450.25, summary(table, 3, "price").max());
    }

    @Test
    void pruningObservable(@TempDir Path tmp) throws Exception {
        StorageEngine engine = new StorageEngine(tmp, 2);
        engine.createTable("trips", TRIPS_SCHEMA);
        Path csv = copyResource(tmp, "trips_sorted_by_distance.csv");
        engine.copyFile("trips", csv.toString());

        // Sorted by distance, partitioned 2 rows at a time:
        //   P0 [12,31]  P1 [88,95]  P2 [140,187]  P3 [210,299]
        // distance > 200 can only match in P3, so P0..P2 must be pruned.
        List<Object[]> rows = engine.select("trips", "distance", Comparison.GREATER_THAN, 200L);
        ScanStats stats = engine.lastScanStats();

        assertEquals(4, stats.partitionsTotal());
        assertEquals(1, stats.partitionsRead());
        assertEquals(3, stats.partitionsPruned());

        assertRowsEqual(
                List.<Object[]>of(GOLDEN_BY_DISTANCE.get(6), GOLDEN_BY_DISTANCE.get(7)),
                rows);
    }

    @Test
    void dataPersistenceAcrossRestart(@TempDir Path tmp) throws Exception {
        Path csv = copyResource(tmp, "trips.csv");

        StorageEngine a = new StorageEngine(tmp, 4);
        a.createTable("trips", TRIPS_SCHEMA);
        a.copyFile("trips", csv.toString());
        assertRowsEqual(GOLDEN, a.select("trips", "distance", Comparison.GREATER_THAN, -1L));

        // A second engine on the same directory must return the same rows,
        // and those rows must still match the golden data.
        StorageEngine b = new StorageEngine(tmp, 4);
        assertRowsEqual(GOLDEN, b.select("trips", "distance", Comparison.GREATER_THAN, -1L));
    }
}
