package dk.itu.swandb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvParserTest {

    private static final List<ColumnSpec> SCHEMA = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    @Test
    void wellFormedLineParses() {
        Object[] row = CsvParser.parseLine("Copenhagen,12,23.5", 1, "f.csv", SCHEMA);
        assertEquals("Copenhagen", row[0]);
        assertEquals(12L, row[1]);
        assertEquals(23.5, row[2]);
    }

    @Test
    void wrongFieldCountRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CsvParser.parseLine("Copenhagen,12", 1, "f.csv", SCHEMA));
        assertEquals("malformed CSV in f.csv line 1: expected 3 fields but got 2", e.getMessage());
    }

    @Test
    void malformedLongRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CsvParser.parseLine("Copenhagen,abc,23.5", 5, "f.csv", SCHEMA));
        assertEquals(
                "malformed CSV in f.csv line 5 column 1 (distance): cannot parse 'abc' as LONG",
                e.getMessage());
    }

    @Test
    void malformedDoubleRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CsvParser.parseLine("Copenhagen,12,oops", 2, "f.csv", SCHEMA));
        assertEquals(
                "malformed CSV in f.csv line 2 column 2 (price): cannot parse 'oops' as DOUBLE",
                e.getMessage());
    }

    @Test
    void readWholeFile(@TempDir Path tmp) throws Exception {
        Path csv = tmp.resolve("data.csv");
        Files.writeString(csv, "Copenhagen,12,23.5\nAarhus,187,301.0\n");
        List<Object[]> rows = CsvParser.read(csv, SCHEMA);
        assertEquals(2, rows.size());
        assertArrayEquals(new Object[]{"Aarhus", 187L, 301.0}, rows.get(1));
    }
}
