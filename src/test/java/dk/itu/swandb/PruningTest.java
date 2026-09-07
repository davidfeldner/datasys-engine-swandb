package dk.itu.swandb;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PruningTest {

    @Test
    void greaterThanPrunes() {
        // distance > 100, partition [12, 31] -> prune
        assertTrue(Pruning.canPrune(Comparison.GREATER_THAN, 100L, 12L, 31L, ColumnType.LONG));
    }

    @Test
    void greaterThanKeeps() {
        // distance > 100, partition [140, 187] -> keep
        assertFalse(Pruning.canPrune(Comparison.GREATER_THAN, 100L, 140L, 187L, ColumnType.LONG));
    }

    @Test
    void greaterThanBoundaryPruned() {
        // predicate constant equals max -> no value strictly above max, so prune
        assertTrue(Pruning.canPrune(Comparison.GREATER_THAN, 31L, 12L, 31L, ColumnType.LONG));
    }

    @Test
    void lessThanPrunes() {
        // distance < 50, partition [88, 95] -> prune
        assertTrue(Pruning.canPrune(Comparison.LESS_THAN, 50L, 88L, 95L, ColumnType.LONG));
    }

    @Test
    void lessThanKeeps() {
        assertFalse(Pruning.canPrune(Comparison.LESS_THAN, 50L, 12L, 31L, ColumnType.LONG));
    }

    @Test
    void equalsPrunesOutside() {
        // city = 'Odense', partition [Aalborg, Copenhagen] -> prune
        assertTrue(Pruning.canPrune(Comparison.EQUALS, "Odense", "Aalborg", "Copenhagen", ColumnType.STRING));
    }

    @Test
    void equalsKeepsInside() {
        // city = 'Odense', partition [Aarhus, Roskilde] -> could contain Odense
        assertFalse(Pruning.canPrune(Comparison.EQUALS, "Odense", "Aarhus", "Roskilde", ColumnType.STRING));
    }

    @Test
    void equalsBoundary() {
        // city = 'Aalborg', partition [Aalborg, Odense] -> min matches constant exactly, keep
        assertFalse(Pruning.canPrune(Comparison.EQUALS, "Aalborg", "Aalborg", "Odense", ColumnType.STRING));
    }

    @Test
    void emptyPartitionIsPruned() {
        assertTrue(Pruning.canPrune(Comparison.GREATER_THAN, 10L, null, null, ColumnType.LONG));
    }
}
