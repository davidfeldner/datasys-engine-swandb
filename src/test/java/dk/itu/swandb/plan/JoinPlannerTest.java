package dk.itu.swandb.plan;

import static dk.itu.swandb.plan.OperatorAssertions.drain;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.swandb.Catalog;
import dk.itu.swandb.ColumnSpec;
import dk.itu.swandb.JoinMode;
import dk.itu.swandb.ScanStats;
import dk.itu.swandb.StorageEngine;
import dk.itu.swandb.enums.ColumnType;
import dk.itu.swandb.enums.Comparison;

/**
 * The Exercise 10 plan, kept reachable as {@code -j syntax}: per-table
 * pipelines with pruning stay below the joins, and the {@code FROM} list
 * folds left to right into three hash joins for four tables. The testbed is
 * the exercise's hand-checkable dataset.
 */
class JoinPlannerTest {

    private static final List<ColumnSpec> ORDERS = List.of(
            new ColumnSpec("o_orderkey", ColumnType.LONG),
            new ColumnSpec("o_custname", ColumnType.STRING),
            new ColumnSpec("o_prodname", ColumnType.STRING),
            new ColumnSpec("o_quantity", ColumnType.LONG));
    private static final List<ColumnSpec> CUSTOMERS = List.of(
            new ColumnSpec("c_name", ColumnType.STRING),
            new ColumnSpec("c_city", ColumnType.STRING));
    private static final List<ColumnSpec> PRODUCTS = List.of(
            new ColumnSpec("p_name", ColumnType.STRING),
            new ColumnSpec("p_price", ColumnType.DOUBLE));
    private static final List<ColumnSpec> CITIES = List.of(
            new ColumnSpec("ct_city", ColumnType.STRING),
            new ColumnSpec("ct_country", ColumnType.STRING),
            new ColumnSpec("ct_population", ColumnType.LONG));

    private static final List<JoinCondition> FOUR_TABLE_JOINS = List.of(
            new JoinCondition("o_custname", "c_name"),
            new JoinCondition("o_prodname", "p_name"),
            new JoinCondition("c_city", "ct_city"));

    @Test
    void fourTablesFoldIntoThreeLeftDeepHashJoinsInFromOrder(@TempDir Path tmp) throws Exception {
        Planner planner = testbedPlanner(tmp, JoinMode.SYNTAX);

        Plan plan = planner.plan(List.of(), FOUR_TABLE_JOINS);

        // The Exercise 10 shape survives as -j syntax: three hash joins whose
        // added tables follow the FROM list, whatever side each builds on.
        assertEquals(3, countJoins(plan.root()));
        assertEquals("joinOrder=orders,customers,products,cities mode=syntax",
                plan.joinOrder().split(" estRows")[0]);

        assertEquals(new ScanStats(6, 6, 0), plan.stats());
        List<Object[]> rows = drain(plan.root());
        assertEquals(4, rows.size(), "one row per order");
        // Columns still come out in FROM-list order.
        assertArrayEquals(new Object[]{100L, "Anna", "Bike", 1L, "Anna", "Copenhagen",
                "Bike", 2999.0, "Copenhagen", "Denmark", 1_400_000L}, rows.get(0));
    }

    @Test
    void aFilterStaysBelowTheJoinsAndStillPrunesItsTable(@TempDir Path tmp) throws Exception {
        Planner planner = testbedPlanner(tmp, JoinMode.SYNTAX);

        Plan plan = planner.plan(
                List.of(new Selection("o_orderkey", Comparison.LESS_THAN, 102L)),
                FOUR_TABLE_JOINS);

        // Three joins still, and the orders filter sits under all of them.
        assertEquals(3, countJoins(plan.root()));
        assertFalse(countFilters(plan.root()) == 0, "the orders filter must stay in the plan");

        // orders has two partitions, [100,101] and [102,103]; the second is pruned.
        assertEquals(new ScanStats(6, 5, 1), plan.stats());
        assertEquals(2, drain(plan.root()).size(), "orders 100 and 101 only");
    }

    @Test
    void aBiggerTableBuildingTheHashMeansTheRowIsReorderedBack(@TempDir Path tmp) throws Exception {
        Planner planner = testbedPlanner(tmp, JoinMode.SYNTAX);

        // orders (4 rows) joins customers (3 rows): the smaller input builds,
        // so the chain's row comes out customers-first. A reordering pass puts
        // the columns back into FROM order before the statement returns them.
        Plan plan = planner.plan(List.of(), FOUR_TABLE_JOINS);

        assertInstanceOf(ColumnReorderOperator.class, plan.root(),
                "the reordered row is mapped back to FROM order");
        assertTrue(plan.joinOrder().contains("reordered=true"), plan.joinOrder());
        List<Object[]> rows = drain(plan.root());
        // orders columns first, then customers, products, cities.
        assertArrayEquals(new Object[]{100L, "Anna", "Bike", 1L, "Anna", "Copenhagen",
                "Bike", 2999.0, "Copenhagen", "Denmark", 1_400_000L}, rows.get(0));
        assertEquals(4, rows.size());
    }

    /** How many hash joins the plan contains. */
    private static int countJoins(Operator operator) {
        if (operator instanceof HashJoinOperator join)
            return 1 + countJoins(join.build()) + countJoins(join.probe());
        if (operator instanceof ColumnReorderOperator reorder) return countJoins(reorder.child());
        if (operator instanceof FilterOperator filter) return countJoins(filter.child());
        return 0;
    }

    /** How many filters the plan contains. */
    private static int countFilters(Operator operator) {
        if (operator instanceof HashJoinOperator join)
            return countFilters(join.build()) + countFilters(join.probe());
        if (operator instanceof ColumnReorderOperator reorder) return countFilters(reorder.child());
        if (operator instanceof FilterOperator filter) return 1 + countFilters(filter.child());
        return 0;
    }

    @Test
    void aTableWithNoConnectingPredicateIsRejectedWithAReorderHint(@TempDir Path tmp) throws Exception {
        Planner planner = testbedPlanner(tmp, JoinMode.OPTIMIZED);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> planner.plan(List.of(),
                        List.of(new JoinCondition("o_custname", "c_name"))));

        assertTrue(e.getMessage().contains("no join predicate connects table"), e.getMessage());
        assertTrue(e.getMessage().contains("reorder the FROM list"), e.getMessage());
    }

    @Test
    void aSecondConnectingPredicateForTheSameTableIsRejected(@TempDir Path tmp) throws Exception {
        Planner planner = testbedPlanner(tmp, JoinMode.OPTIMIZED);

        // products is connected to the rest of the list twice: once by
        // o_prodname = p_name and once by c_name = p_name, while orders,
        // customers and cities form a path of their own. The list is
        // connected, so the double connection is the failure that remains.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> planner.plan(List.of(), List.of(
                        new JoinCondition("o_custname", "c_name"),
                        new JoinCondition("c_city", "ct_city"),
                        new JoinCondition("o_prodname", "p_name"),
                        new JoinCondition("c_name", "p_name"))));

        assertTrue(e.getMessage().contains("more than one join predicate"), e.getMessage());
    }

    // --- helpers -------------------------------------------------------

    private static Planner testbedPlanner(Path tmp, JoinMode mode) throws IOException {
        Path data = tmp.resolve("data");
        StorageEngine engine = new StorageEngine(data, 2);
        engine.createTable("orders", ORDERS);
        engine.createTable("customers", CUSTOMERS);
        engine.createTable("products", PRODUCTS);
        engine.createTable("cities", CITIES);

        engine.copyFile("orders", write(tmp, "orders.csv", """
                100,Anna,Bike,1
                101,Bob,Helmet,2
                102,Carol,Bike,1
                103,Anna,Helmet,1
                """));
        engine.copyFile("customers", write(tmp, "customers.csv", """
                Anna,Copenhagen
                Bob,Aarhus
                Carol,Copenhagen
                """));
        engine.copyFile("products", write(tmp, "products.csv", """
                Bike,2999.0
                Helmet,299.0
                """));
        engine.copyFile("cities", write(tmp, "cities.csv", """
                Copenhagen,Denmark,1400000
                Aarhus,Denmark,290000
                """));
        engine.close();

        Catalog catalog = new Catalog(data);
        List<Planner.TableInput> inputs = new ArrayList<>();
        for (String table : List.of("orders", "customers", "products", "cities")) {
            inputs.add(input(data, catalog.getTable(table)));
        }
        return new Planner(inputs, mode);
    }

    private static Planner.TableInput input(Path data, Catalog.TableEntry table) {
        return new Planner.TableInput(table.name, table.columns, table.partitions,
                data.resolve(table.dataFile));
    }

    private static String write(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file.toString();
    }

}
