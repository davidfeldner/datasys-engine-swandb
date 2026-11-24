package dk.itu.swandb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.swandb.TestSupport.Capture;
import dk.itu.swandb.TestSupport.SessionCapture;

/**
 * Exercise 11's requirement 6, the metamorphic test: the planner rearranges
 * the plan, so the result must not change.
 *
 * <p>The week 10 testbed runs twice, once under {@code -j syntax} and once
 * under {@code -j optimized}. The sorted rows must be identical, the plan
 * must differ, and every executed join must leave one log line carrying both
 * {@code estRows} and {@code rowsOut}.
 */
class JoinOptimizerIT {

    private static final String SETUP = """
            CREATE TABLE orders (o_orderkey LONG, o_custname STRING, o_prodname STRING, o_quantity LONG);
            CREATE TABLE products (p_name STRING, p_price DOUBLE);
            CREATE TABLE customers (c_name STRING, c_city STRING);
            CREATE TABLE cities (ct_city STRING, ct_country STRING, ct_population LONG);
            COPY orders FROM '%s';
            COPY products FROM '%s';
            COPY customers FROM '%s';
            COPY cities FROM '%s';
            """;

    /** Three- and four-table queries, so both chain lengths are covered. */
    private static final String QUERIES = """
            SELECT * FROM orders, customers WHERE o_custname = c_name;
            SELECT * FROM orders, customers, products
                WHERE o_custname = c_name AND o_prodname = p_name;
            SELECT * FROM orders, customers, products, cities
                WHERE o_custname = c_name AND o_prodname = p_name AND c_city = ct_city;
            SELECT * FROM customers, orders, cities, products
                WHERE o_custname = c_name AND c_city = ct_city AND o_prodname = p_name;
            SELECT * FROM orders, customers, products
                WHERE o_custname = c_name AND o_prodname = p_name AND c_city = 'Copenhagen';
            """;

    @Test
    void bothFlagValuesReturnIdenticalSortedRowsFromDifferentPlans(@TempDir Path tmp) throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "");
        String setup = setupScript(tmp, tag);

        SessionCapture syntax = run(setup, "-j", "syntax", tmp);
        SessionCapture optimized = run(setup, "-j", "optimized", tmp);
        List<String> syntaxRows = rowsOf(syntax.capture());
        List<String> optimizedRows = rowsOf(optimized.capture());

        // The metamorphic property itself: same multiset of rows.
        List<String> sortedSyntax = sorted(syntaxRows);
        List<String> sortedOptimized = sorted(optimizedRows);
        assertEquals(sortedSyntax, sortedOptimized,
                "the optimizer must not change the result");
        assertFalse(sortedSyntax.isEmpty(), "the testbed queries return rows");

        // ... and the plan really was different for at least one statement.
        List<String> syntaxOrders = joinOrders(syntax.sessionId());
        List<String> optimizedOrders = joinOrders(optimized.sessionId());
        assertFalse(syntaxOrders.isEmpty(), "the syntax run logged its join order");
        assertFalse(optimizedOrders.isEmpty(), "the optimized run logged its join order");
        boolean differed = false;
        for (int i = 0; i < Math.min(syntaxOrders.size(), optimizedOrders.size()); i++) {
            if (!syntaxOrders.get(i).equals(optimizedOrders.get(i))) differed = true;
        }
        assertTrue(differed, "expected a reordered plan, got " + syntaxOrders + " and " + optimizedOrders);
    }

    @Test
    void everyExecutedJoinLogsItsEstimateNextToItsActualOutput(@TempDir Path tmp) throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "");
        String setup = setupScript(tmp, tag);
        String session = run(setup, "-j", "optimized", tmp).sessionId();

        List<String> joinLines = Files.readAllLines(Path.of("logs", "engine.log")).stream()
                .filter(line -> line.contains(session))
                .filter(line -> line.split(",", -1)[5].equals("HashJoinOperator"))
                .filter(line -> line.contains("join="))
                .toList();

        // Two three-table queries and one four-table query: 2 + 2 + 3 joins.
        assertTrue(joinLines.size() >= 7, "expected a line per executed join, got " + joinLines);
        for (String line : joinLines) {
            String message = line.split(",", -1)[6];
            assertTrue(message.contains("estRows="), "missing estimate: " + line);
            assertTrue(message.contains("rowsOut="), "missing actual: " + line);
            assertTrue(message.contains("join="), "missing join number: " + line);
            // The statement is attributable through the statementNumber field.
            assertNotEquals("0", line.split(",", -1)[2], "the join belongs to a statement: " + line);
            assertEquals(7, line.split(",", -1).length, "seven CSV fields: " + line);
        }

        // The estimates are also next to the actuals per statement.
        List<String> summaries = Files.readAllLines(Path.of("logs", "engine.log")).stream()
                .filter(line -> line.contains(session))
                .filter(line -> line.split(",", -1)[5].equals("Executor"))
                .filter(line -> line.split(",", -1)[6].startsWith("statement=SELECT"))
                .toList();
        assertFalse(summaries.isEmpty());
        for (String summary : summaries) {
            String message = summary.split(",", -1)[6];
            assertTrue(message.contains("joinOrder="), "the chosen order per statement: " + summary);
            assertTrue(message.contains("mode="), "the mode per statement: " + summary);
        }
    }

    @Test
    void theSyntaxFlagReproducesTheFromListOrder(@TempDir Path tmp) throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "");
        String setup = setupScript(tmp, tag);
        String session = run(setup, "-j", "syntax", tmp).sessionId();

        List<String> orders = joinOrders(session);
        // The four-table statements name the tables in FROM-list order. The
        // log replaces commas inside a message, so an order reads with ';'.
        assertTrue(orders.stream().anyMatch(o -> o.startsWith("joinOrder=orders;customers;products;cities")),
                orders.toString());
        assertTrue(orders.stream().anyMatch(o -> o.startsWith("joinOrder=customers;orders;cities;products")),
                orders.toString());
        for (String order : orders) {
            assertTrue(order.contains("mode=syntax"), order);
        }
    }

    @Test
    void anUnknownJoinModeIsAUsageError(@TempDir Path tmp) throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "");
        Capture capture = TestSupport.runEngine(
                new String[]{"-j", "sideways", "-c", "SELECT * FROM nope;"}, tmp.resolve("data"));

        assertEquals(2, capture.exitCode(), capture.stderr());
        assertTrue(capture.stderr().contains("unknown join mode"), capture.stderr());
        assertTrue(capture.stderr().contains("optimized"), capture.stderr());
    }

    // --- helpers -------------------------------------------------------

    private static String setupScript(Path tmp, String tag) throws Exception {
        Path orders = csv(tmp, "orders-" + tag + ".csv", """
                100,Anna,Bike,1
                101,Bob,Helmet,2
                102,Carol,Bike,1
                103,Anna,Helmet,1
                """);
        Path products = csv(tmp, "products-" + tag + ".csv", """
                Bike,2999.0
                Helmet,299.0
                """);
        Path customers = csv(tmp, "customers-" + tag + ".csv", """
                Anna,Copenhagen
                Bob,Aarhus
                Carol,Copenhagen
                """);
        Path cities = csv(tmp, "cities-" + tag + ".csv", """
                Copenhagen,Denmark,1400000
                Aarhus,Denmark,290000
                """);
        String setup = SETUP.formatted(orders, products, customers, cities) + QUERIES;
        Path script = tmp.resolve("queries-" + tag + ".sql");
        Files.writeString(script, setup);
        return script.toString();
    }

    private static SessionCapture run(String script, String flag, String mode, Path tmp) {
        // Each run gets its own data directory, so the CREATE TABLEs succeed
        // and repeated runs cannot see each other's state.
        return TestSupport.runEngineWithSession(new String[]{flag, mode, "-f", script},
                tmp.resolve("data-" + mode));
    }

    private static List<String> rowsOf(Capture capture) {
        assertEquals(0, capture.exitCode(), capture.stderr());
        List<String> rows = new ArrayList<>();
        for (String line : capture.stdout().lines().toList()) {
            if (!line.isBlank()) rows.add(line);
        }
        return rows;
    }

    private static List<String> sorted(List<String> rows) {
        List<String> out = new ArrayList<>(rows);
        out.sort(null);
        return out;
    }

    /** The join-order lines of one run, in statement order. */
    private static List<String> joinOrders(String sessionId) {
        try {
            return Files.readAllLines(Path.of("logs", "engine.log")).stream()
                    .filter(line -> line.contains(sessionId))
                    .filter(line -> line.split(",", -1)[5].equals("Executor"))
                    .map(line -> line.split(",", -1)[6])
                    .filter(message -> message.startsWith("statement=SELECT"))
                    .map(JoinOptimizerIT::orderOf)
                    .filter(order -> !order.isEmpty())
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The {@code joinOrder=<tables> mode=<mode>} segment of a SELECT summary
     * line, without the estimate fields that follow it.
     */
    private static String orderOf(String summary) {
        int start = summary.indexOf("joinOrder=");
        if (start < 0) return "";
        int end = summary.indexOf(" estRows", start);
        if (end < 0) end = summary.indexOf(" durationMs", start);
        return end < 0 ? summary.substring(start) : summary.substring(start, end);
    }

    private static Path csv(Path dir, String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }
}
