package dk.itu.swandb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.swandb.enums.ColumnType;

/**
 * Exercise 11's requirement 1: the per-partition NDV is exact, the merge rule
 * is the documented maximum, and the statistic is collected during COPY and
 * visible in the log as the partition is written.
 */
class NdvTest {

    private static final List<ColumnSpec> TRIPS = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    @Test
    void aPartitionWithKnownDuplicatesYieldsTheExactCount() {
        assertEquals(3, DistinctValues.count(List.of("a", "b", "a", "c", "b")));
        assertEquals(1, DistinctValues.count(List.of(7L, 7L, 7L)));
        assertEquals(0, DistinctValues.count(List.of()));
        assertEquals(1, DistinctValues.count(List.of(1L)));
    }

    @Test
    void doublesCountTheWayTheJoinComparesThem() {
        // A hash join compares keys with equals, so -0.0 and 0.0 never match
        // each other and are two distinct values; every NaN equals every
        // other NaN and is one.
        assertEquals(2, DistinctValues.count(List.of(0.0, -0.0)));
        assertEquals(1, DistinctValues.count(List.of(Double.NaN, Double.NaN)));
        // 1.5 and 1.50 are the same double, so one value.
        assertEquals(1, DistinctValues.count(List.of(1.5, 1.50)));
    }

    @Test
    void stringsCountTooBecauseJoinKeysAreOftenStrings() {
        assertEquals(2, DistinctValues.count(List.of("Bike", "Helmet", "Bike")));
    }

    @Test
    void rowsMergeBySum() {
        assertEquals(0, Valuation.tableRows(List.of()));
        assertEquals(2000, Valuation.tableRows(List.of(1000, 0, 500, 500)));
        assertThrows(IllegalArgumentException.class, () -> Valuation.tableRows(List.of(-1)));
    }

    @Test
    void ndvMergesByMaximumSoOverlappingPartitionsCannotOvercount() {
        // The same three names in four partitions: max says 4, sum would say 12.
        assertEquals(4, Valuation.tableNdv(List.of(4L, 4L, 4L, 4L)));
        // Disjoint partitions still merge to the largest partition.
        assertEquals(5, Valuation.tableNdv(List.of(2L, 5L, 3L)));
        // No partitions at all: 1, so every divisor stays positive.
        assertEquals(1, Valuation.tableNdv(List.of()));
        // An empty partition records 0 but must not drag the table's NDV down.
        assertEquals(5, Valuation.tableNdv(List.of(0L, 5L)));
        assertThrows(IllegalArgumentException.class, () -> Valuation.tableNdv(List.of(-1L)));
    }

    @Test
    void copyRecordsTheNdvOfEveryColumnOfEveryPartition(@TempDir Path tmp) throws Exception {
        StorageEngine engine = new StorageEngine(tmp.resolve("data"), 3);
        engine.createTable("trips", TRIPS);
        // Three partitions of three rows: cities repeat across partitions.
        Path csv = tmp.resolve("trips.csv");
        Files.writeString(csv, """
                Copenhagen,12,23.5
                Aarhus,187,301.0
                Copenhagen,95,120.75
                Odense,140,210.0
                Aarhus,210,340.5
                Odense,31,45.0
                Roskilde,88,99.99
                Esbjerg,299,450.25
                Roskilde,301,500.0
                """);
        engine.copyFile("trips", csv.toString());
        engine.close();

        Catalog catalog = new Catalog(tmp.resolve("data"));
        List<Catalog.PartitionEntry> partitions = catalog.getTable("trips").partitions;
        assertEquals(3, partitions.size());
        // Partition 0 holds Copenhagen, Aarhus, Copenhagen: 2 distinct cities.
        assertEquals(2L, partitions.get(0).summary("city").ndv());
        assertEquals(3L, partitions.get(0).summary("distance").ndv());
        assertEquals(3L, partitions.get(0).summary("price").ndv());
        // Partition 2 holds Roskilde, Esbjerg, Roskilde.
        assertEquals(2L, partitions.get(2).summary("city").ndv());

        // The table's merged NDV is the maximum over its partitions.
        long tableCityNdv = Valuation.tableNdv(partitions.stream()
                .map(p -> p.summary("city").ndv()).toList());
        assertEquals(2L, tableCityNdv);
    }

    @Test
    void everyNdvIsLoggedWhenThePartitionIsCreated(@TempDir Path tmp) throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "");
        String table = "t" + tag;
        StorageEngine engine = new StorageEngine(tmp.resolve("data"), 2);
        engine.createTable(table, TRIPS);
        Path csv = tmp.resolve("rows-" + tag + ".csv");
        Files.writeString(csv, "Copenhagen,12,23.5\nCopenhagen,13,24.5\n");
        engine.copyFile(table, csv.toString());
        engine.close();

        List<String> lines = Files.readAllLines(Path.of("logs", "engine.log")).stream()
                .filter(line -> line.contains(tag))
                .filter(line -> line.split(",", -1)[6].contains("partition="))
                .toList();
        assertTrue(lines.size() >= 3, "one summary line per column: " + lines);
        String cityLine = lines.stream().filter(l -> l.contains("column=city")).findFirst().orElseThrow();
        assertTrue(cityLine.contains("rows=2"), cityLine);
        assertTrue(cityLine.contains("ndv=1"), "two identical cities are one distinct value: " + cityLine);
        for (String line : lines) {
            assertEquals(7, line.split(",", -1).length, "the CSV log line stays seven fields: " + line);
        }
    }
}
