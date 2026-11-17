package dk.itu.swandb.plan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The hash join over {@link TestListOperator} stubs: the cases that reveal
 * the per-probe-row cursor are duplicates on both sides, unmatched rows, and
 * an empty side.
 */
class HashJoinOperatorTest {

    private static final List<Object[]> BUILD = List.of(
            new Object[]{1L, "build-a"},
            new Object[]{2L, "build-b"},
            new Object[]{1L, "build-c"});

    @Test
    void duplicatesOnBothSidesMultiply() {
        // Two build rows carry key 1; three probe rows carry key 1: 2 * 3 rows.
        List<Object[]> probe = List.of(
                new Object[]{1L, "probe-x"},
                new Object[]{1L, "probe-y"},
                new Object[]{1L, "probe-z"});

        HashJoinOperator join = new HashJoinOperator(
                new TestListOperator(BUILD), new TestListOperator(probe), 0, 0);

        List<Object[]> rows = drain(join);

        assertEquals(6, rows.size());
        assertArrayEquals(new Object[]{1L, "build-a", 1L, "probe-x"}, rows.get(0));
        assertArrayEquals(new Object[]{1L, "build-c", 1L, "probe-x"}, rows.get(1));
        assertArrayEquals(new Object[]{1L, "build-a", 1L, "probe-y"}, rows.get(2));
        assertArrayEquals(new Object[]{1L, "build-c", 1L, "probe-z"}, rows.get(5));
    }

    @Test
    void aCursorIntoTheMatchListIsKeptBetweenNextCalls() {
        // One probe row emits two build rows across three next() calls: the
        // second match must survive the call that returned the first.
        HashJoinOperator join = new HashJoinOperator(
                new TestListOperator(List.<Object[]>of(
                        new Object[]{1L, "a"}, new Object[]{1L, "b"})),
                new TestListOperator(List.<Object[]>of(new Object[]{1L, "probe"})), 0, 0);

        join.open();
        try {
            assertArrayEquals(new Object[]{1L, "a", 1L, "probe"}, join.next());
            assertArrayEquals(new Object[]{1L, "b", 1L, "probe"}, join.next());
            assertNull(join.next());
        } finally {
            join.close();
        }
    }

    @Test
    void rowsWithoutAPartnerAreDropped() {
        List<Object[]> probe = List.of(
                new Object[]{2L, "probe-b"},
                new Object[]{3L, "probe-missing"});

        HashJoinOperator join = new HashJoinOperator(
                new TestListOperator(BUILD), new TestListOperator(probe), 0, 0);

        assertRows(List.<Object[]>of(new Object[]{2L, "build-b", 2L, "probe-b"}), drain(join));
    }

    @Test
    void anEmptyBuildSideYieldsNothing() {
        HashJoinOperator join = new HashJoinOperator(
                new TestListOperator(List.of()),
                new TestListOperator(List.<Object[]>of(new Object[]{1L, "probe"})), 0, 0);

        assertEquals(List.of(), drain(join));
    }

    @Test
    void anEmptyProbeSideYieldsNothing() {
        HashJoinOperator join = new HashJoinOperator(
                new TestListOperator(BUILD), new TestListOperator(List.of()), 0, 0);

        assertEquals(List.of(), drain(join));
    }

    private static List<Object[]> drain(Operator operator) {
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

    private static void assertRows(List<Object[]> expected, List<Object[]> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++)
            assertArrayEquals(expected.get(i), actual.get(i), "row " + i);
    }
}
