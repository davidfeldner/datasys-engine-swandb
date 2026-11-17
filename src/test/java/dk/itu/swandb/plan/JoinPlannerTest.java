package dk.itu.swandb.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import dk.itu.swandb.ScanStats;
import dk.itu.swandb.StorageEngine;
import dk.itu.swandb.enums.ColumnType;
import dk.itu.swandb.enums.Comparison;

/**
 * The Exercise 10 planner: per-table pipelines with pruning stay below the
 * joins, and the {@code FROM} list folds left to right into three hash joins
 * for four tables. The testbed is the exercise's hand-checkable dataset.
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
        Planner planner = testbedPlanner(tmp);

        Plan plan = planner.plan(List.of(), FOUR_TABLE_JOINS);

        HashJoinOperator cities = assertInstanceOf(HashJoinOperator.class, plan.root());
        HashJoinOperator products = assertInstanceOf(HashJoinOperator.class, cities.build());
        HashJoinOperator customers = assertInstanceOf(HashJoinOperator.class, products.build());

        assertInstanceOf(ScanOperator.class, customers.build(), "orders is the first pipeline");
        assertInstanceOf(ScanOperator.class, customers.probe(), "customers probes");
        assertInstanceOf(ScanOperator.class, products.probe(), "products probes");
        assertInstanceOf(ScanOperator.class, cities.probe(), "cities probes");

        // Build keys index the intermediate row; probe keys index the new table.
        assertEquals(1, customers.buildKey(), "o_custname in the orders row");
        assertEquals(0, customers.probeKey(), "c_name in the customers row");
        assertEquals(2, products.buildKey(), "o_prodname in the orders+customers row");
        assertEquals(0, products.probeKey(), "p_name in the products row");
        assertEquals(5, cities.buildKey(), "c_city in the orders+customers+products row");
        assertEquals(0, cities.probeKey(), "ct_city in the cities row");

        assertEquals(new ScanStats(6, 6, 0), plan.stats());
        assertEquals(4, drain(plan.root()).size(), "one row per order");
    }

    @Test
    void aFilterStaysBelowTheJoinsAndStillPrunesItsTable(@TempDir Path tmp) throws Exception {
        Planner planner = testbedPlanner(tmp);

        Plan plan = planner.plan(
                List.of(new Selection("o_orderkey", Comparison.LESS_THAN, 102L)),
                FOUR_TABLE_JOINS);

        HashJoinOperator cities = assertInstanceOf(HashJoinOperator.class, plan.root());
        HashJoinOperator products = assertInstanceOf(HashJoinOperator.class, cities.build());
        HashJoinOperator customers = assertInstanceOf(HashJoinOperator.class, products.build());
        assertInstanceOf(FilterOperator.class, customers.build(),
                "the orders filter is below every join");

        // orders has two partitions, [100,101] and [102,103]; the second is pruned.
        assertEquals(new ScanStats(6, 5, 1), plan.stats());
        assertEquals(2, drain(plan.root()).size(), "orders 100 and 101 only");
    }

    @Test
    void aTableWithNoConnectingPredicateIsRejectedWithAReorderHint(@TempDir Path tmp) throws Exception {
        Planner planner = testbedPlanner(tmp);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> planner.plan(List.of(),
                        List.of(new JoinCondition("o_custname", "c_name"))));

        assertTrue(e.getMessage().contains("no join predicate connects table"), e.getMessage());
        assertTrue(e.getMessage().contains("reorder the FROM list"), e.getMessage());
    }

    @Test
    void aSecondConnectingPredicateForTheSameTableIsRejected(@TempDir Path tmp) throws Exception {
        Planner planner = testbedPlanner(tmp);

        // products is connected to the already-joined tables twice: once by
        // o_prodname = p_name and once by c_city = p_name.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> planner.plan(List.of(), List.of(
                        new JoinCondition("o_custname", "c_name"),
                        new JoinCondition("o_prodname", "p_name"),
                        new JoinCondition("c_city", "p_name"))));

        assertTrue(e.getMessage().contains("more than one join predicate"), e.getMessage());
    }

    // --- helpers -------------------------------------------------------

    private static Planner testbedPlanner(Path tmp) throws IOException {
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
        return new Planner(inputs);
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
}
