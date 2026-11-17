package dk.itu.swandb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.swandb.TestSupport.Capture;

/**
 * Exercise 10's testbed through the front door: a script creates and loads
 * the four tables and runs the four queries, and each query's sorted rows
 * must equal the sorted rows DuckDB returns for the same data.
 */
class JoinIT {

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

    private static final List<String> QUERY_1 = List.of(
            "100,Anna,Bike,1,Anna,Copenhagen",
            "101,Bob,Helmet,2,Bob,Aarhus",
            "102,Carol,Bike,1,Carol,Copenhagen",
            "103,Anna,Helmet,1,Anna,Copenhagen");

    private static final List<String> QUERY_2 = List.of(
            "100,Anna,Bike,1,Anna,Copenhagen,Bike,2999.0",
            "101,Bob,Helmet,2,Bob,Aarhus,Helmet,299.0",
            "102,Carol,Bike,1,Carol,Copenhagen,Bike,2999.0",
            "103,Anna,Helmet,1,Anna,Copenhagen,Helmet,299.0");

    private static final List<String> QUERY_3 = List.of(
            "100,Anna,Bike,1,Anna,Copenhagen,Bike,2999.0,Copenhagen,Denmark,1400000",
            "101,Bob,Helmet,2,Bob,Aarhus,Helmet,299.0,Aarhus,Denmark,290000",
            "102,Carol,Bike,1,Carol,Copenhagen,Bike,2999.0,Copenhagen,Denmark,1400000",
            "103,Anna,Helmet,1,Anna,Copenhagen,Helmet,299.0,Copenhagen,Denmark,1400000");

    private static final List<String> QUERY_4 = List.of(
            "100,Anna,Bike,1,Anna,Copenhagen,Bike,2999.0",
            "102,Carol,Bike,1,Carol,Copenhagen,Bike,2999.0",
            "103,Anna,Helmet,1,Anna,Copenhagen,Helmet,299.0");

    @Test
    void theFourTestbedQueriesMatchTheOracleSorted(@TempDir Path tmp) throws Exception {
        String orders = csv(tmp, "orders.csv", """
                100,Anna,Bike,1
                101,Bob,Helmet,2
                102,Carol,Bike,1
                103,Anna,Helmet,1
                """);
        String products = csv(tmp, "products.csv", """
                Bike,2999.0
                Helmet,299.0
                """);
        String customers = csv(tmp, "customers.csv", """
                Anna,Copenhagen
                Bob,Aarhus
                Carol,Copenhagen
                """);
        String cities = csv(tmp, "cities.csv", """
                Copenhagen,Denmark,1400000
                Aarhus,Denmark,290000
                """);

        Path script = tmp.resolve("join.sql");
        Files.writeString(script, SETUP.formatted(orders, products, customers, cities) + """
                SELECT * FROM orders, customers WHERE o_custname = c_name;
                SELECT * FROM orders, customers, products
                    WHERE o_custname = c_name AND o_prodname = p_name;
                SELECT * FROM orders, customers, products, cities
                    WHERE o_custname = c_name AND o_prodname = p_name AND c_city = ct_city;
                SELECT * FROM orders, customers, products
                    WHERE o_custname = c_name AND o_prodname = p_name AND c_city = 'Copenhagen';
                """);

        Capture capture = run(new String[]{"-f", script.toString()}, tmp.resolve("data"));

        assertEquals(0, capture.exitCode(), capture.stderr());
        List<String> lines = capture.stdout().lines().toList();
        assertEquals(QUERY_1.size() + QUERY_2.size() + QUERY_3.size() + QUERY_4.size(),
                lines.size(), capture.stdout());

        assertSorted(QUERY_1, lines.subList(0, 4));
        assertSorted(QUERY_2, lines.subList(4, 8));
        assertSorted(QUERY_3, lines.subList(8, 12));
        assertSorted(QUERY_4, lines.subList(12, 15));
    }

    @Test
    void anUnconnectedFromListFailsWithAReorderHint(@TempDir Path tmp) throws Exception {
        String orders = csv(tmp, "orders.csv", "100,Anna,Bike,1\n");
        String products = csv(tmp, "products.csv", "Bike,2999.0\n");
        String customers = csv(tmp, "customers.csv", "Anna,Copenhagen\n");
        String cities = csv(tmp, "cities.csv", "Copenhagen,Denmark,1400000\n");
        Path script = tmp.resolve("cross.sql");
        Files.writeString(script,
                SETUP.formatted(orders, products, customers, cities)
                        + "SELECT * FROM orders, products;\n");

        Capture capture = run(new String[]{"-f", script.toString()}, tmp.resolve("data"));

        assertEquals(1, capture.exitCode());
        assertEquals("", capture.stdout());
        assertTrue(capture.stderr().contains("reorder the FROM list"), capture.stderr());
    }

    private static void assertSorted(List<String> expected, List<String> actual) {
        List<String> want = new ArrayList<>(expected);
        List<String> got = new ArrayList<>(actual);
        want.sort(null);
        got.sort(null);
        assertEquals(want, got);
    }

    private static String csv(Path dir, String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file.toString();
    }

    private static Capture run(String[] args, Path dataDir) {
        return TestSupport.runEngine(args, dataDir);
    }
}
