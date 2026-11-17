package dk.itu.swandb.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dk.itu.swandb.ColumnSpec;
import dk.itu.swandb.enums.ColumnType;
import dk.itu.swandb.plan.ColumnResolver.ResolvedColumn;

/**
 * The one answer both the binder and the planner read: which table owns an
 * unqualified name, and where the column sits in the concatenated row.
 */
class ColumnResolverTest {

    private static final ColumnResolver TESTBED = new ColumnResolver(
            List.of("orders", "customers", "products"),
            List.of(
                    List.of(new ColumnSpec("o_orderkey", ColumnType.LONG),
                            new ColumnSpec("o_custname", ColumnType.STRING),
                            new ColumnSpec("o_prodname", ColumnType.STRING)),
                    List.of(new ColumnSpec("c_name", ColumnType.STRING),
                            new ColumnSpec("c_city", ColumnType.STRING)),
                    List.of(new ColumnSpec("p_name", ColumnType.STRING),
                            new ColumnSpec("p_price", ColumnType.DOUBLE))));

    @Test
    void mapsANameToItsTableAndItsIndexOfTheConcatenatedRow() {
        ResolvedColumn city = TESTBED.resolve("c_city");

        assertEquals(1, city.tableIndex());
        assertEquals("customers", city.tableName());
        assertEquals(1, city.tableColumnIndex());
        assertEquals(4, city.rowIndex());
        assertEquals(ColumnType.STRING, city.column().type());
    }

    @Test
    void indexesContinueAcrossTablesInFromOrder() {
        assertEquals(0, TESTBED.resolve("o_orderkey").rowIndex());
        assertEquals(3, TESTBED.tableOffset(1));
        assertEquals(5, TESTBED.resolve("p_name").rowIndex());
        assertEquals(6, TESTBED.resolve("p_price").rowIndex());
        assertEquals(7, TESTBED.rowWidth());
    }

    @Test
    void anUnknownNameIsAnError() {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> TESTBED.resolve("nope"));

        assertTrue(e.getMessage().contains("unknown column: nope"), e.getMessage());
    }

    @Test
    void aNameInTwoTablesIsAmbiguous() {
        ColumnResolver clashing = new ColumnResolver(
                List.of("left", "right"),
                List.of(List.of(new ColumnSpec("id", ColumnType.LONG)),
                        List.of(new ColumnSpec("id", ColumnType.LONG))));

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> clashing.resolve("id"));

        assertTrue(e.getMessage().contains("ambiguous column: id"), e.getMessage());
        assertTrue(e.getMessage().contains("left"), e.getMessage());
        assertTrue(e.getMessage().contains("right"), e.getMessage());
    }

    @Test
    void aNameTwiceInOneTableIsAmbiguousToo() {
        ColumnResolver duplicated = new ColumnResolver(
                List.of("one"),
                List.of(List.of(new ColumnSpec("id", ColumnType.LONG),
                        new ColumnSpec("id", ColumnType.STRING))));

        assertThrows(IllegalArgumentException.class, () -> duplicated.resolve("id"));
    }

    @Test
    void aFromListNeedsAtLeastOneTable() {
        assertThrows(IllegalArgumentException.class,
                () -> new ColumnResolver(List.of(), List.of()));
    }
}
