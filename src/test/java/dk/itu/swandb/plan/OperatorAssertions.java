package dk.itu.swandb.plan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared row plumbing for operator tests: drain a tree in one go, then assert
 * the rows it produced. Keeping it here stops every operator test from
 * repeating the same open/next/close loop.
 */
public final class OperatorAssertions {

    private OperatorAssertions() {
    }

    /** Drains an operator tree, always closing it. */
    public static List<Object[]> drain(Operator operator) {
        List<Object[]> out = new ArrayList<>();
        operator.open();
        try {
            Object[] row;
            while ((row = operator.next()) != null) out.add(row);
        } finally {
            operator.close();
        }
        return out;
    }

    /** Asserts the exact row list, in order. */
    public static void assertRows(List<Object[]> expected, List<Object[]> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++)
            assertArrayEquals(expected.get(i), actual.get(i), "row " + i);
    }
}
