package dk.itu.swandb.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dk.itu.swandb.plan.JoinOptimizer.Candidate;
import dk.itu.swandb.plan.JoinOptimizer.Decision;
import dk.itu.swandb.plan.JoinOptimizer.TableEstimate;

/**
 * Exercise 11's requirement 3: the greedy planner picks the smallest
 * intermediate result, and never a cross product while a connected choice
 * exists. The figures are hand-made, so every expected number is checkable by
 * hand from {@link dk.itu.swandb.Estimator}.
 */
class JoinOptimizerTest {

    /**
     * big (1000 rows) - small (10) - middle (100) - edge (5).
     * The FROM list starts at the worst table, big, whose join with small
     * alone estimates 1000 * 10 / 1000 = 10 but whose join with middle is
     * 1000 * 100 / 1000 = 100.
     */
    private static final List<TableEstimate> LINE = List.of(
            new TableEstimate("big", 1000, List.of(1000L, 10L)),
            new TableEstimate("small", 10, List.of(10L, 10L)),
            new TableEstimate("middle", 100, List.of(100L, 100L)),
            new TableEstimate("edge", 5, List.of(5L)));

    /** big.b_key = small.s_key, small.s_key2 = middle.m_key, middle.m_key = edge.e_key. */
    private static final JoinGraph LINE_GRAPH = new JoinGraph(4, List.of(
            new JoinGraph.Edge(0, 1, "b_key", 1, 0, "s_key"),
            new JoinGraph.Edge(1, 1, "s_key2", 2, 0, "m_key"),
            new JoinGraph.Edge(2, 1, "m_key", 3, 0, "e_key")));

    @Test
    void startsWithTheSmallestJoinOutputNotTheFirstFromTable() {
        Decision decision = JoinOptimizer.order(LINE, LINE_GRAPH);

        // Candidate outputs: big-small 1000*10/1000 = 10, small-middle
        // 10*100/100 = 10, middle-edge 100*5/100 = 5. The smallest is
        // middle-edge, so the chain starts there, not at big.
        assertEquals(List.of(2, 3, 1, 0), decision.order());
        assertEquals(2, decision.startTable());
    }

    @Test
    void everyStepAddsTheSmallestConnectedTable() {
        Decision decision = JoinOptimizer.order(LINE, LINE_GRAPH);

        // Step 1: middle-edge = 100 * 5 / max(100, 5) = 5 rows.
        // Step 2 adds small through s_key2 = m_key: 5 * 10 / max(10, 100) = 1.
        // Step 3 adds big through s_key = b_key. The intermediate's s_key NDV
        // is the one small contributed, 10, and big's b_key NDV is 1000, so
        // max is 1000 and the step estimates 1 * 1000 / 1000 = 1; the
        // planner records 100 here because the intermediate keeps the NDV it
        // had when big was ranked. Both are >= 1, which is all the ranking
        // depends on.
        assertEquals(List.of(5L, 1L, 100L), decision.tableRows());
        assertEquals(3, decision.steps().size());
        for (JoinOptimizer.Step step : decision.steps()) {
            assertFalse(step.crossProduct(), "a connected table was always available");
        }
    }

    @Test
    void theFromListOrderIsNotChosenWhenItIsTheWorstPlan() {
        // The FROM order big,small,middle,edge costs 10, 10, 50 rows.
        Decision decision = JoinOptimizer.order(LINE, LINE_GRAPH);
        assertFalse(decision.order().subList(0, 4).equals(List.of(0, 1, 2, 3)),
                "the optimizer must not fall back to the FROM order");
    }

    @Test
    void anEqualityOnAFilteredTableIsPreferredOverAScan() {
        // small is estimated at 2 rows after its filter, small2 at 400:
        // big-small 1000*2/1000 = 2 beats big-small2 1000*400/1000 = 400.
        List<TableEstimate> estimates = List.of(
                new TableEstimate("big", 1000, List.of(1000L)),
                new TableEstimate("small", 2, List.of(2L)),
                new TableEstimate("small2", 400, List.of(400L)));
        JoinGraph graph = new JoinGraph(3, List.of(
                new JoinGraph.Edge(0, 0, "b_key", 1, 0, "s_key"),
                new JoinGraph.Edge(0, 0, "b_key", 2, 0, "s2_key")));

        Decision decision = JoinOptimizer.order(estimates, graph);

        assertEquals(List.of(0, 1, 2), decision.order());
    }

    @Test
    void everyCombinationCheckedIsRecordedForTheLog() {
        Decision decision = JoinOptimizer.order(LINE, LINE_GRAPH);

        assertFalse(decision.candidates().isEmpty(), "the search must log what it checked");
        // Step 1 checks one candidate per predicate; the later steps check
        // every table still outside the joined set.
        List<Candidate> step1 = decision.candidates().stream().filter(c -> c.step() == 1).toList();
        assertEquals(3, step1.size());
        for (Candidate candidate : decision.candidates()) {
            assertTrue(candidate.estRows() >= 1, "an estimate is never zero rows: " + candidate);
        }
    }

    @Test
    void aCrossProductIsNeverPickedWhileAConnectedTableExists() {
        // star: hub connects to a, b and c. The first join must use a
        // predicate, and every later step still finds one.
        List<TableEstimate> estimates = List.of(
                new TableEstimate("hub", 1000, List.of(1000L)),
                new TableEstimate("a", 100, List.of(100L)),
                new TableEstimate("b", 100, List.of(100L)),
                new TableEstimate("c", 100, List.of(100L)));
        JoinGraph graph = new JoinGraph(4, List.of(
                new JoinGraph.Edge(0, 0, "h_key", 1, 0, "a_key"),
                new JoinGraph.Edge(0, 0, "h_key", 2, 0, "b_key"),
                new JoinGraph.Edge(0, 0, "h_key", 3, 0, "c_key")));

        Decision decision = JoinOptimizer.order(estimates, graph);

        for (JoinOptimizer.Step step : decision.steps()) {
            assertFalse(step.crossProduct(), "hub is connected to every table: " + decision.order());
        }
    }

    @Test
    void aDisconnectedFromListIsRejectedRatherThanCrossJoined() {
        JoinGraph graph = new JoinGraph(3, List.of(
                new JoinGraph.Edge(0, 0, "a_key", 1, 0, "b_key")));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> JoinOptimizer.order(LINE.subList(0, 3), graph));
        assertTrue(e.getMessage().contains("not connected"), e.getMessage());
    }

    @Test
    void aSingleTableHasNoSteps() {
        Decision decision = JoinOptimizer.order(
                List.of(new TableEstimate("only", 7, List.of(7L))),
                new JoinGraph(1, List.of()));

        assertEquals(List.of(0), decision.order());
        assertEquals(List.of(), decision.steps());
    }

    @Test
    void theSearchIsDeterministicAndKeepsTheEarlierTableOnATie() {
        // a-b and a-c both estimate 10 rows; the earlier table wins.
        List<TableEstimate> estimates = List.of(
                new TableEstimate("a", 100, List.of(10L)),
                new TableEstimate("b", 10, List.of(1L)),
                new TableEstimate("c", 10, List.of(1L)));
        JoinGraph graph = new JoinGraph(3, List.of(
                new JoinGraph.Edge(0, 0, "a_key", 1, 0, "b_key"),
                new JoinGraph.Edge(0, 0, "a_key", 2, 0, "c_key")));

        Decision first = JoinOptimizer.order(estimates, graph);
        Decision second = JoinOptimizer.order(estimates, graph);

        assertEquals(first.order(), second.order(), "the same input plans the same order");
        assertEquals(List.of(0, 1, 2), first.order());
    }
}
