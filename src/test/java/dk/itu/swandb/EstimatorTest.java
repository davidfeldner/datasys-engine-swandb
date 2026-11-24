package dk.itu.swandb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import dk.itu.swandb.enums.Comparison;

/**
 * Exercise 11's requirement 2: the estimates are pure functions over catalog
 * numbers, checked by hand. Every case states the arithmetic it expects.
 */
class EstimatorTest {

    @Test
    void aBaseTableIsTheRowsOfItsSurvivingPartitions() {
        assertEquals(1000, Estimator.base(1000L));
        // Zero rows still estimates at least one, so a later join in the chain
        // cannot collapse the whole plan to zero.
        assertEquals(1, Estimator.base(0L));
        assertThrows(IllegalArgumentException.class, () -> Estimator.base(-1L));
        assertThrows(IllegalArgumentException.class, () -> Estimator.base(null));
    }

    @Test
    void aFilterIsTheRowsDividedByTheColumnsNdv() {
        assertEquals(100, Estimator.filter(1000, 10, Comparison.EQUALS));
        assertEquals(250, Estimator.filter(1000, 4, Comparison.EQUALS));
        // 7 / 3 rounds to 2, and 5.5 rounds up to 6.
        assertEquals(2, Estimator.filter(7, 3, Comparison.EQUALS));
        assertEquals(6, Estimator.filter(11, 2, Comparison.EQUALS));
    }

    @Test
    void anNdvOfOneKeepsEveryRowAndZeroIsTreatedTheSameWay() {
        // Every value identical: an equality matches all rows.
        assertEquals(500, Estimator.filter(500, 1, Comparison.EQUALS));
        assertEquals(500, Estimator.filter(500, 0, Comparison.EQUALS));
    }

    @Test
    void anInequalityUsesSelingersOneThird() {
        // Selinger et al., SIGMOD 1979: a comparison on a range column keeps 1/3.
        assertEquals(Estimator.INEQUALITY_SELECTIVITY, 1.0 / 3);
        assertEquals(3, Estimator.filter(10, 4, Comparison.LESS_THAN));
        assertEquals(3, Estimator.filter(10, 4, Comparison.GREATER_THAN));
        assertEquals(333, Estimator.filter(1000, 7, Comparison.LESS_THAN));
        // A filter never estimates zero rows.
        assertEquals(1, Estimator.filter(1, 9, Comparison.LESS_THAN));
    }

    @Test
    void theNdvNeverMattersForInequalities() {
        assertEquals(Estimator.filter(900, 2, Comparison.LESS_THAN),
                Estimator.filter(900, 300, Comparison.LESS_THAN));
    }

    @Test
    void aJoinIsTheProductDividedByTheLargerNdv() {
        // Lecture 10's formula: |R x S| / max(ndv(R.a), ndv(S.b)).
        assertEquals(40, Estimator.join(100, 20, 50, 10));   // 2000 / 50
        assertEquals(40, Estimator.join(20, 100, 10, 50));   // order does not matter
        assertEquals(100, Estimator.join(100, 100, 100, 100));
        // Equal NDVs: 100 * 50 / 100 = 50, the foreign-key case where every
        // row of the smaller table finds its partner.
        assertEquals(50, Estimator.join(100, 50, 100, 50));
    }

    @Test
    void aJoinCannotDivideByZero() {
        // Both NDVs missing: the max(..., 1) floor makes it a cross product.
        assertEquals(2000, Estimator.join(100, 20, 0, 0));
        assertEquals(2000, Estimator.join(100, 20, 0, 1));
    }

    @Test
    void aJoinEstimatesAtLeastOneRowAndRejectsNegatives() {
        assertEquals(1, Estimator.join(0, 0, 5, 5));
        assertThrows(IllegalArgumentException.class, () -> Estimator.join(-1, 2, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> Estimator.join(1, 1, -2, 1));
        assertThrows(IllegalArgumentException.class, () -> Estimator.filter(-1, 1, Comparison.EQUALS));
        assertThrows(IllegalArgumentException.class, () -> Estimator.filter(1, 1, null));
    }
}
