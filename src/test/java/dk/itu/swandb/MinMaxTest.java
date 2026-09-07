package dk.itu.swandb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class MinMaxTest {

    @Test
    void singleValueLong() {
        MinMax.Result r = MinMax.compute(ColumnType.LONG, List.of(7L));
        assertEquals(7L, r.min());
        assertEquals(7L, r.max());
    }

    @Test
    void singleValueNegativeDouble() {
        MinMax.Result r = MinMax.compute(ColumnType.DOUBLE, List.of(-1.5));
        assertEquals(-1.5, r.min());
        assertEquals(-1.5, r.max());
    }

    @Test
    void multipleValuesLong() {
        MinMax.Result r = MinMax.compute(ColumnType.LONG, List.of(3L, 1L, 4L, 1L, 5L, 9L, 2L, 6L));
        assertEquals(1L, r.min());
        assertEquals(9L, r.max());
    }

    @Test
    void multipleValuesString() {
        MinMax.Result r = MinMax.compute(ColumnType.STRING, List.of("Copenhagen", "Aalborg", "Esbjerg"));
        assertEquals("Aalborg", r.min());
        assertEquals("Esbjerg", r.max());
    }

    @Test
    void emptyList() {
        MinMax.Result r = MinMax.compute(ColumnType.LONG, List.of());
        assertEquals(null, r.min());
        assertEquals(null, r.max());
    }
}
