package dk.itu.swandb.plan;

import static dk.itu.swandb.TestSupport.TRIPS_SCHEMA;
import static dk.itu.swandb.TestSupport.copyResource;
import static dk.itu.swandb.plan.OperatorAssertions.assertRows;
import static dk.itu.swandb.plan.OperatorAssertions.drain;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.swandb.Catalog;
import dk.itu.swandb.ScanStats;
import dk.itu.swandb.StorageEngine;
import dk.itu.swandb.enums.Comparison;

/**
 * Pruning belongs to the planner, and it is asserted here through
 * {@link ScanStats} while the data file is never opened. The golden input is
 * the distance-sorted trips file with {@code maxRowsPerPartition = 2}, so the
 * four partitions are [12,31], [88,95], [140,187], [210,299].
 */
class PlannerTest {

    @Test
    void keepsExactlyThePartitionsThatCanMatch(@TempDir Path tmp) throws Exception {
        Planner planner = plannerOverSortedTrips(tmp);

        Plan plan = planner.plan(
                List.of(new Selection("distance", Comparison.GREATER_THAN, 200L)), List.of());

        assertStats(new ScanStats(4, 1, 3), plan.stats());

        assertRows(List.of(
                new Object[]{"Aalborg", 210L, 340.5},
                new Object[]{"Esbjerg", 299L, 450.25}), drain(plan.root()));
    }

    @Test
    void aLessThanKeepsTheTwoLowerPartitions(@TempDir Path tmp) throws Exception {
        Planner planner = plannerOverSortedTrips(tmp);

        Plan plan = planner.plan(
                List.of(new Selection("distance", Comparison.LESS_THAN, 100L)), List.of());

        assertStats(new ScanStats(4, 2, 2), plan.stats());
    }

    @Test
    void aFullyPrunedQueryPlansAnEmptyScanAndReadsNothing(@TempDir Path tmp) throws Exception {
        Planner planner = plannerOverSortedTrips(tmp);

        Plan plan = planner.plan(
                List.of(new Selection("distance", Comparison.GREATER_THAN, 1000L)), List.of());

        assertStats(new ScanStats(4, 0, 4), plan.stats());
        assertEquals(List.of(), drain(plan.root()));
    }

    @Test
    void aWhereBecomesAFilterOverAScan(@TempDir Path tmp) throws Exception {
        Planner planner = plannerOverSortedTrips(tmp);

        Plan plan = planner.plan(
                List.of(new Selection("distance", Comparison.GREATER_THAN, 200L)), List.of());

        assertInstanceOf(FilterOperator.class, plan.root());
    }

    @Test
    void noWhereBecomesABareScanOverEveryPartition(@TempDir Path tmp) throws Exception {
        Planner planner = plannerOverSortedTrips(tmp);

        Plan plan = planner.plan(List.of(), List.of());

        assertInstanceOf(ScanOperator.class, plan.root());
        assertStats(new ScanStats(4, 4, 0), plan.stats());
        assertEquals(8, drain(plan.root()).size());
    }

    @Test
    void aMissingSummaryIsAnErrorInsteadOfASilentPrune(@TempDir Path tmp) throws Exception {
        Catalog.TableEntry table = sortedTrips(tmp);
        // Corrupt the pinned metadata: partition 0 no longer carries any summaries.
        List<Catalog.PartitionEntry> partitions = new ArrayList<>(table.partitions);
        partitions.set(0, summaryless(partitions.get(0)));
        Planner planner = new Planner(List.of(
                new Planner.TableInput("trips", table.columns, partitions, dataPath(tmp, table))));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> planner.plan(
                List.of(new Selection("distance", Comparison.GREATER_THAN, 200L)), List.of()));
        assertTrue(e.getMessage().contains("missing min/max summary"), e.getMessage());
    }

    @Test
    void aNullBoundIsAnErrorInsteadOfASilentPrune(@TempDir Path tmp) throws Exception {
        Catalog.TableEntry table = sortedTrips(tmp);
        // Column 1 is distance; wipe only its lower bound.
        List<Catalog.PartitionEntry> partitions = new ArrayList<>(table.partitions);
        partitions.set(0, withNullDistanceMin(partitions.get(0)));
        Planner planner = new Planner(List.of(
                new Planner.TableInput("trips", table.columns, partitions, dataPath(tmp, table))));

        assertThrows(IllegalStateException.class, () -> planner.plan(
                List.of(new Selection("distance", Comparison.GREATER_THAN, 200L)), List.of()));
    }

    private static Catalog.PartitionEntry summaryless(Catalog.PartitionEntry entry) {
        Catalog.PartitionEntry copy = entry.copy();
        copy.columns.clear();
        return copy;
    }

    private static Catalog.PartitionEntry withNullDistanceMin(Catalog.PartitionEntry entry) {
        Catalog.PartitionEntry copy = entry.copy();
        copy.columns.get(1).min = null;
        return copy;
    }

    private static Planner plannerOverSortedTrips(Path tmp) throws Exception {
        Catalog.TableEntry table = sortedTrips(tmp);
        return new Planner(List.of(
                new Planner.TableInput("trips", table.columns, table.partitions, dataPath(tmp, table))));
    }

    private static Path dataPath(Path tmp, Catalog.TableEntry table) {
        return tmp.resolve("data").resolve(table.dataFile);
    }

    private static Catalog.TableEntry sortedTrips(Path tmp) throws Exception {
        Path dataDir = tmp.resolve("data");
        StorageEngine engine = new StorageEngine(dataDir, 2);
        engine.createTable("trips", TRIPS_SCHEMA);
        Path csv = copyResource(tmp, "trips_sorted_by_distance.csv");
        engine.copyFile("trips", csv.toString());
        engine.close();
        return new Catalog(dataDir).getTable("trips");
    }

    private static void assertStats(ScanStats expected, ScanStats actual) {
        assertEquals(expected.partitionsTotal(), actual.partitionsTotal(), "partitionsTotal");
        assertEquals(expected.partitionsRead(), actual.partitionsRead(), "partitionsRead");
        assertEquals(expected.partitionsPruned(), actual.partitionsPruned(), "partitionsPruned");
    }

}
