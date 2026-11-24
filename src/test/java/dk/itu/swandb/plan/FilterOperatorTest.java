package dk.itu.swandb.plan;

import static dk.itu.swandb.plan.OperatorAssertions.assertRows;
import static dk.itu.swandb.plan.OperatorAssertions.drain;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import dk.itu.swandb.ColumnSpec;
import dk.itu.swandb.enums.ColumnType;
import dk.itu.swandb.enums.Comparison;

class FilterOperatorTest {

    private static final ColumnSpec CITY = new ColumnSpec("city", ColumnType.STRING);
    private static final ColumnSpec DISTANCE = new ColumnSpec("distance", ColumnType.LONG);

    private static final List<Object[]> ROWS = List.of(
            new Object[]{"Copenhagen", 12L},
            new Object[]{"Aarhus", 187L},
            new Object[]{"Odense", 95L});

    @Test
    void keepsRowsThatPassAGreaterThan() {
        FilterOperator filter = new FilterOperator(
                new TestListOperator(ROWS), 1, DISTANCE, Comparison.GREATER_THAN, 100L);

        assertRows(List.<Object[]>of(ROWS.get(1)), drain(filter));
    }

    @Test
    void keepsRowsThatPassAnEquals() {
        FilterOperator filter = new FilterOperator(
                new TestListOperator(ROWS), 0, CITY, Comparison.EQUALS, "Odense");

        assertRows(List.<Object[]>of(ROWS.get(2)), drain(filter));
    }

    @Test
    void lessThanMatchesWeekTwoSemantics() {
        // value < 100 -> Copenhagen (12) and Odense (95), not Aarhus (187).
        FilterOperator filter = new FilterOperator(
                new TestListOperator(ROWS), 1, DISTANCE, Comparison.LESS_THAN, 100L);

        assertRows(List.of(ROWS.get(0), ROWS.get(2)), drain(filter));
    }

    @Test
    void anEmptyChildYieldsNothing() {
        FilterOperator filter = new FilterOperator(
                new TestListOperator(List.of()), 1, DISTANCE, Comparison.GREATER_THAN, 0L);

        assertEquals(List.of(), drain(filter));
    }


    // Guards that the helper itself iterates in order and then stops.
    @Test
    void testListOperatorIsExhausted() {
        TestListOperator list = new TestListOperator(ROWS);
        list.open();
        assertArrayEquals(ROWS.get(0), list.next());
        assertArrayEquals(ROWS.get(1), list.next());
        assertArrayEquals(ROWS.get(2), list.next());
        assertNull(list.next());
    }
}
