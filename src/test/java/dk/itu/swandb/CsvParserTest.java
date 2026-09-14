package dk.itu.swandb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void stringFieldKeepsSurroundingSpaces() {
        // Spaces inside a string field are data, not padding.
        Object[] row = CsvParser.parseLine(" Copenhagen ,12,23.5", 1, "f.csv", SCHEMA);
        assertEquals(" Copenhagen ", row[0]);
    }

    @Test
    void whitespaceOnlyStringFieldIsKept() {
        Object[] row = CsvParser.parseLine(" ,12,23.5", 1, "f.csv", SCHEMA);
        assertEquals(" ", row[0]);
    }

    @Test
    void emptyStringFieldIsKept() {
        Object[] row = CsvParser.parseLine(",12,23.5", 1, "f.csv", SCHEMA);
        assertEquals("", row[0]);
    }

    @Test
    void paddedStringIsNotEqualToUnpadded() {
        Object[] padded = CsvParser.parseLine("Copenhagen ,12,23.5", 1, "f.csv", SCHEMA);
        Object[] plain = CsvParser.parseLine("Copenhagen,12,23.5", 1, "f.csv", SCHEMA);
        assertNotEquals(padded[0], plain[0]);
        // ...and the padding changes lexicographic order, as it must.
        assertTrue(((String) plain[0]).compareTo((String) padded[0]) < 0);
    }

    @Test
    void numericFieldsTolerateSurroundingWhitespace() {
        // Whitespace around a numeric literal does not change the number.
        Object[] row = CsvParser.parseLine("Copenhagen, 12 , 23.5 ", 1, "f.csv", SCHEMA);
        assertEquals("Copenhagen", row[0]);
        assertEquals(12L, row[1]);
        assertEquals(23.5, row[2]);
    }

    @Test
    void malformedNumericErrorQuotesOriginalField() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CsvParser.parseLine("Copenhagen, xyz ,23.5", 3, "f.csv", SCHEMA));
        assertEquals(
                "malformed CSV in f.csv line 3 column 1 (distance): cannot parse ' xyz ' as LONG",
                e.getMessage());
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
