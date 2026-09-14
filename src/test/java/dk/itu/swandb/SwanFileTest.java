package dk.itu.swandb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.swandb.enums.ColumnType;

/**
 * Unit tests for the offset-addressable {@link SwanFile} helpers that make
 * partition-level I/O pruning possible.
 */
class SwanFileTest {

    private static final List<ColumnSpec> SCHEMA = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    private static SwanFile.Partition partitionOf(Object[][] rows) {
        List<List<Object>> columns = new ArrayList<>(SCHEMA.size());
        for (int c = 0; c < SCHEMA.size(); c++) {
            List<Object> values = new ArrayList<>(rows.length);
            for (Object[] row : rows) {
                values.add(row[c]);
            }
            columns.add(values);
        }
        return new SwanFile.Partition(rows.length, columns);
    }

    @Test
    void writeRecordsAscendingPartitionOffsets(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("data.swan");
        SwanFile.Partition first = partitionOf(new Object[][]{{"Copenhagen", 12L, 23.5}});
        SwanFile.Partition second = partitionOf(new Object[][]{
                {"Aarhus", 187L, 301.0},
                {"Odense", 95L, 120.75}});

        List<Long> offsets = SwanFile.write(file, SCHEMA, List.of(first, second));

        assertEquals(2, offsets.size());
        assertTrue(offsets.get(0) >= SwanFile.HEADER_BYTES, "first offset must be past the header");
        assertTrue(offsets.get(1) > offsets.get(0), "offsets must be recorded in on-disk order");
        assertEquals(second.rowCount, SwanFile.readPartition(file, SCHEMA, offsets.get(1)).rowCount);
    }

    @Test
    void readPartitionReturnsOnlyTheRequestedPartition(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("data.swan");
        SwanFile.Partition first = partitionOf(new Object[][]{{"Copenhagen", 12L, 23.5}});
        SwanFile.Partition second = partitionOf(new Object[][]{
                {"Aarhus", 187L, 301.0},
                {"Odense", 95L, 120.75}});
        List<Long> offsets = SwanFile.write(file, SCHEMA, List.of(first, second));

        SwanFile.Partition read = SwanFile.readPartition(file, SCHEMA, offsets.get(1));

        assertEquals(2, read.rowCount);
        assertEquals(List.of("Aarhus", "Odense"), read.columnValues.get(0));
        assertEquals(List.of(187L, 95L), read.columnValues.get(1));
        assertEquals(List.of(301.0, 120.75), read.columnValues.get(2));
    }
}
