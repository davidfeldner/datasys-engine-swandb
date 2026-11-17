package dk.itu.swandb.sql;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.swandb.ColumnSpec;
import dk.itu.swandb.StorageEngine;
import dk.itu.swandb.enums.ColumnType;
import dk.itu.swandb.sql.ast.Statement;

/**
 * Exercise 10's binder rules over the four-table testbed: unqualified names
 * resolve to exactly one table, and a join must connect two different tables
 * whose columns have the same type.
 */
class JoinBinderIT {

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

    private final SqlParser parser = new SqlParser();

    @Test
    void theFourTableTestbedBinds(@TempDir Path tmp) {
        StorageEngine engine = testbed(tmp);

        assertDoesNotThrow(() -> bindScript(engine, """
                SELECT * FROM orders, customers WHERE o_custname = c_name;
                SELECT * FROM orders, customers, products
                    WHERE o_custname = c_name AND o_prodname = p_name;
                SELECT * FROM orders, customers, products, cities
                    WHERE o_custname = c_name AND o_prodname = p_name AND c_city = ct_city;
                SELECT * FROM orders, customers, products
                    WHERE o_custname = c_name AND o_prodname = p_name AND c_city = 'Copenhagen';
                """));
    }

    @Test
    void anUnknownColumnInTheFromListIsRejected(@TempDir Path tmp) {
        StorageEngine engine = testbed(tmp);

        IllegalArgumentException e = bindFailure(engine,
                "SELECT * FROM orders, customers WHERE nope = c_name;");

        assertTrue(e.getMessage().contains("unknown column: nope"), e.getMessage());
    }

    @Test
    void aNameInTwoTablesIsAmbiguous(@TempDir Path tmp) {
        StorageEngine engine = testbed(tmp);
        engine.createTable("dup_left", List.of(new ColumnSpec("shared", ColumnType.LONG)));
        engine.createTable("dup_right", List.of(new ColumnSpec("shared", ColumnType.LONG)));

        IllegalArgumentException e = bindFailure(engine,
                "SELECT * FROM dup_left, dup_right WHERE shared = 1;");

        assertTrue(e.getMessage().contains("ambiguous column: shared"), e.getMessage());
    }

    @Test
    void aJoinAcrossMismatchedTypesIsRejected(@TempDir Path tmp) {
        StorageEngine engine = testbed(tmp);

        IllegalArgumentException e = bindFailure(engine,
                "SELECT * FROM orders, products WHERE o_quantity = p_price;");

        assertTrue(e.getMessage().contains("join column type mismatch"), e.getMessage());
        assertTrue(e.getMessage().contains("LONG"), e.getMessage());
        assertTrue(e.getMessage().contains("DOUBLE"), e.getMessage());
    }

    @Test
    void aJoinWhoseSidesLandInTheSameTableIsRejected(@TempDir Path tmp) {
        StorageEngine engine = testbed(tmp);

        IllegalArgumentException e = bindFailure(engine,
                "SELECT * FROM orders, customers WHERE o_orderkey = o_quantity;");

        assertTrue(e.getMessage().contains("different tables"), e.getMessage());
    }

    // --- helpers -------------------------------------------------------

    private static StorageEngine testbed(Path dir) {
        StorageEngine engine = new StorageEngine(dir);
        engine.createTable("orders", ORDERS);
        engine.createTable("customers", CUSTOMERS);
        engine.createTable("products", PRODUCTS);
        engine.createTable("cities", CITIES);
        return engine;
    }

    private void bindScript(StorageEngine engine, String sql) {
        Binder binder = new Binder(engine);
        for (Statement statement : parser.parse(sql))
            binder.bind(statement);
    }

    private IllegalArgumentException bindFailure(StorageEngine engine, String sql) {
        List<Statement> statements = parser.parse(sql);
        assertEquals(1, statements.size(), sql);
        Binder binder = new Binder(engine);
        return assertThrows(IllegalArgumentException.class, () -> binder.bind(statements.get(0)));
    }
}
