package dk.itu.swandb.plan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.swandb.Catalog;
import dk.itu.swandb.ColumnSpec;
import dk.itu.swandb.ScanStats;
import dk.itu.swandb.StorageEngine;
import dk.itu.swandb.enums.ColumnType;
import dk.itu.swandb.enums.Comparison;

/**
 * Pruning belongs to the planner, and it is asserted here through
 * {@link ScanStats} while the data file is never opened. The golden input is
 * the distance-sorted trips file with {@code maxRowsPerPartition = 2}, so the
 * four partitions are [12,31], [88,95], [140,187], [210,299].
 */
class PlannerTest {

    private static final List<ColumnSpec> TRIPS_SCHEMA = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    @Test
    void keepsExactlyThePartitionsThatCanMatch(@TempDir Path tmp) throws Exception {
        Planner planner = plannerOverSortedTrips(tmp);

        Plan plan = planner.plan("trips",
                Optional.of(new Selection("distance", Comparison.GREATER_THAN, 200L)));

        assertStats(new ScanStats(4, 1, 3), plan.stats());

        ScanOperator scan = (ScanOperator) ((FilterOperator) plan.root()).child();
        assertEquals(1, scan.partitions().size());
        assertEquals(3, scan.partitions().get(0).index());
        assertRows(List.of(
                new Object[]{"Aalborg", 210L, 340.5},
                new Object[]{"Esbjerg", 299L, 450.25}), drain(plan.root()));
    }

    @Test
    void aLessThanKeepsTheTwoLowerPartitions(@TempDir Path tmp) throws Exception {
        Planner planner = plannerOverSortedTrips(tmp);

        Plan plan = planner.plan("trips",
                Optional.of(new Selection("distance", Comparison.LESS_THAN, 100L)));

        assertStats(new ScanStats(4, 2, 2), plan.stats());
    }

    @Test
    void aFullyPrunedQueryPlansAnEmptyScanAndReadsNothing(@TempDir Path tmp) throws Exception {
        Planner planner = plannerOverSortedTrips(tmp);

        Plan plan = planner.plan("trips",
                Optional.of(new Selection("distance", Comparison.GREATER_THAN, 1000L)));

        assertStats(new ScanStats(4, 0, 4), plan.stats());
        ScanOperator scan = (ScanOperator) ((FilterOperator) plan.root()).child();
        assertTrue(scan.partitions().isEmpty());
        assertEquals(List.of(), drain(plan.root()));
    }

    @Test
    void aWhereBecomesAFilterOverAScan(@TempDir Path tmp) throws Exception {
        Planner planner = plannerOverSortedTrips(tmp);

        Plan plan = planner.plan("trips",
                Optional.of(new Selection("distance", Comparison.GREATER_THAN, 200L)));

        FilterOperator filter = assertInstanceOf(FilterOperator.class, plan.root());
        assertInstanceOf(ScanOperator.class, filter.child());
    }

    @Test
    void noWhereBecomesABareScanOverEveryPartition(@TempDir Path tmp) throws Exception {
        Planner planner = plannerOverSortedTrips(tmp);

        Plan plan = planner.plan("trips", Optional.empty());

        ScanOperator scan = assertInstanceOf(ScanOperator.class, plan.root());
        assertEquals(4, scan.partitions().size());
        assertStats(new ScanStats(4, 4, 0), plan.stats());
        assertEquals(8, drain(plan.root()).size());
    }

    @Test
    void aMissingSummaryIsAnErrorInsteadOfASilentPrune(@TempDir Path tmp) throws Exception {
        Path dataDir = tmp.resolve("data");
        Catalog catalog = sortedTripsCatalog(tmp);
        // Corrupt the catalog: partition 0 no longer carries any summaries.
        catalog.getTable("trips").partitions.get(0).columns.clear();
        Planner planner = new Planner(catalog, dataDir);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> planner.plan("trips",
                Optional.of(new Selection("distance", Comparison.GREATER_THAN, 200L))));
        assertTrue(e.getMessage().contains("missing min/max summary"), e.getMessage());
    }

    @Test
    void aNullBoundIsAnErrorInsteadOfASilentPrune(@TempDir Path tmp) throws Exception {
        Path dataDir = tmp.resolve("data");
        Catalog catalog = sortedTripsCatalog(tmp);
        // Column 1 is distance; wipe only its lower bound.
        catalog.getTable("trips").partitions.get(0).columns.get(1).min = null;
        Planner planner = new Planner(catalog, dataDir);

        assertThrows(IllegalStateException.class, () -> planner.plan("trips",
                Optional.of(new Selection("distance", Comparison.GREATER_THAN, 200L))));
    }

    private static Planner plannerOverSortedTrips(Path tmp) throws Exception {
        Path dataDir = tmp.resolve("data");
        return new Planner(sortedTripsCatalog(tmp), dataDir);
    }

    private static Catalog sortedTripsCatalog(Path tmp) throws Exception {
        Path dataDir = tmp.resolve("data");
        StorageEngine engine = new StorageEngine(dataDir, 2);
        engine.createTable("trips", TRIPS_SCHEMA);
        Path csv = copyResource(tmp, "trips_sorted_by_distance.csv");
        engine.copyFile("trips", csv.toString());
        return new Catalog(dataDir);
    }

    private static Path copyResource(Path tmp, String resourceName) throws Exception {
        Path target = tmp.resolve(resourceName);
        try (InputStream in = PlannerTest.class.getClassLoader().getResourceAsStream(resourceName)) {
            assertTrue(in != null, "missing test resource " + resourceName);
            Files.write(target, in.readAllBytes());
        }
        return target;
    }

    private static void assertStats(ScanStats expected, ScanStats actual) {
        assertEquals(expected.partitionsTotal(), actual.partitionsTotal(), "partitionsTotal");
        assertEquals(expected.partitionsRead(), actual.partitionsRead(), "partitionsRead");
        assertEquals(expected.partitionsPruned(), actual.partitionsPruned(), "partitionsPruned");
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
