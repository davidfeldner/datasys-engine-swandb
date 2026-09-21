package dk.itu.swandb.plan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.swandb.Catalog;
import dk.itu.swandb.ColumnSpec;
import dk.itu.swandb.StorageEngine;
import dk.itu.swandb.enums.ColumnType;

/**
 * The scan is a plain reader: it returns exactly the rows of the partitions
 * it is handed and never sees the predicate. The fully pruned case is the
 * empty list, which must read nothing and return nothing.
 */
class ScanOperatorTest {

    private static final List<ColumnSpec> TRIPS_SCHEMA = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    @Test
    void returnsExactlyTheRowsOfTheHandedPartitions(@TempDir Path tmp) throws Exception {
        Catalog.TableEntry table = loadTrips(tmp, 2);
        Path dataPath = tmp.resolve("data").resolve(table.dataFile);

        // The sorted golden data, maxRowsPerPartition = 2, partitions 1 and 3.
        ScanOperator scan = new ScanOperator(dataPath, table.columns,
                List.of(table.partitions.get(1), table.partitions.get(3)));

        List<Object[]> expected = List.of(
                new Object[]{"Copenhagen", 88L, 99.99},
                new Object[]{"Odense", 95L, 120.75},
                new Object[]{"Aalborg", 210L, 340.5},
                new Object[]{"Esbjerg", 299L, 450.25});
        assertRows(expected, drain(scan));
    }

    @Test
    void anEmptyPartitionListReadsNothingAndReturnsNothing(@TempDir Path tmp) throws Exception {
        Catalog.TableEntry table = loadTrips(tmp, 2);
        Path dataPath = tmp.resolve("data").resolve(table.dataFile);

        ScanOperator scan = new ScanOperator(dataPath, table.columns, List.of());

        assertEquals(List.of(), drain(scan));
    }

    @Test
    void aFullListReturnsEveryRowInPartitionOrder(@TempDir Path tmp) throws Exception {
        Catalog.TableEntry table = loadTrips(tmp, 2);
        Path dataPath = tmp.resolve("data").resolve(table.dataFile);

        ScanOperator scan = new ScanOperator(dataPath, table.columns, table.partitions);

        List<Object[]> expected = List.of(
                new Object[]{"Copenhagen", 12L, 23.5},
                new Object[]{"Roskilde", 31L, 45.0},
                new Object[]{"Copenhagen", 88L, 99.99},
                new Object[]{"Odense", 95L, 120.75},
                new Object[]{"Copenhagen", 140L, 210.0},
                new Object[]{"Aarhus", 187L, 301.0},
                new Object[]{"Aalborg", 210L, 340.5},
                new Object[]{"Esbjerg", 299L, 450.25});
        assertRows(expected, drain(scan));
    }

    private static Catalog.TableEntry loadTrips(Path tmp, int maxRowsPerPartition) throws Exception {
        StorageEngine engine = new StorageEngine(tmp.resolve("data"), maxRowsPerPartition);
        engine.createTable("trips", TRIPS_SCHEMA);
        Path csv = copyResource(tmp, "trips_sorted_by_distance.csv");
        engine.copyFile("trips", csv.toString());
        return new Catalog(tmp.resolve("data")).getTable("trips");
    }

    private static Path copyResource(Path tmp, String resourceName) throws Exception {
        Path target = tmp.resolve(resourceName);
        try (InputStream in = ScanOperatorTest.class.getClassLoader().getResourceAsStream(resourceName)) {
            assertTrue(in != null, "missing test resource " + resourceName);
            Files.write(target, in.readAllBytes());
        }
        return target;
    }

    private static List<Object[]> drain(Operator operator) {
        List<Object[]> out = new ArrayList<>();
        operator.open();
        try {
            Object[] row;
            while ((row = operator.next()) != null) {
                out.add(row);
            }
        } finally {
            operator.close();
        }
        return out;
    }

    private static void assertRows(List<Object[]> expected, List<Object[]> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertArrayEquals(expected.get(i), actual.get(i), "row " + i);
        }
    }
}
