package dk.itu.swandb.plan;

import static dk.itu.swandb.TestSupport.TRIPS_SCHEMA;
import static dk.itu.swandb.TestSupport.copyResource;
import static dk.itu.swandb.plan.OperatorAssertions.assertRows;
import static dk.itu.swandb.plan.OperatorAssertions.drain;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.swandb.Catalog;
import dk.itu.swandb.StorageEngine;

/**
 * The scan is a plain reader: it returns exactly the rows of the partitions
 * it is handed and never sees the predicate. The fully pruned case is the
 * empty list, which must read nothing and return nothing.
 */
class ScanOperatorTest {

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
        // The catalog is written when the engine closes; likelier than not,
        // this test reads the table before it closes, so ask for the write.
        engine.close();
        return new Catalog(tmp.resolve("data")).getTable("trips");
    }

}
