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
import dk.itu.swandb.sql.ast.CreateTableStatement;
import dk.itu.swandb.sql.ast.Statement;

/**
 * Integration tests for the binder against a real {@link StorageEngine} on
 * a temporary directory: the catalog the binder reads is the catalog the
 * engine persisted, so a statement that binds is one execution can run.
 * Runs under the Failsafe plugin (mvn verify), not Surefire.
 */
class BinderIT {

    private static final List<ColumnSpec> TRIPS_SCHEMA = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    /** The script of Exercise 3 Task 1, which must parse and bind as a whole. */
    private static final String TASK_1_SCRIPT = """
            CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
            COPY trips FROM 'trips.csv';
            SELECT * FROM trips WHERE distance > 100;
            SELECT * FROM trips;
            """;

    private final SqlParser parser = new SqlParser();

    @Test
    void task1ScriptBindsAgainstTheCatalog(@TempDir Path tmp) {
        StorageEngine engine = engineWithTrips(tmp);

        assertDoesNotThrow(() -> bindScript(engine, TASK_1_SCRIPT));
    }

    @Test
    void validStatementsOfEveryShapeAndTypeBind(@TempDir Path tmp) {
        StorageEngine engine = engineWithTrips(tmp);

        assertDoesNotThrow(() -> bindScript(engine, """
                CREATE TABLE cities (name STRING, population LONG);
                COPY trips FROM 'trips.csv';
                SELECT * FROM trips;
                SELECT * FROM trips WHERE distance > 100;
                SELECT * FROM trips WHERE city = 'Odense';
                SELECT * FROM trips WHERE price < 50.0;
                SELECT * FROM trips WHERE price = -1.5;
                """));
    }

    @Test
    void createTableOfANewNameBindsButIsNotExecuted(@TempDir Path tmp) {
        StorageEngine engine = engineWithTrips(tmp);

        bindScript(engine, "CREATE TABLE cities (name STRING);");

        // Binding validates the statement, it does not run it: the table
        // only exists once execution has created it.
        assertThrows(IllegalArgumentException.class, () -> engine.schema("cities"));
    }

    @Test
    void createTableOfAnExistingNameStillBinds(@TempDir Path tmp) {
        StorageEngine engine = engineWithTrips(tmp);

        // Whether the table already exists is execution's check, where it can
        // be made atomically with the create.
        assertDoesNotThrow(() -> bindScript(engine, "CREATE TABLE trips (city STRING);"));
    }

    @Test
    void unknownTableThrows(@TempDir Path tmp) {
        StorageEngine engine = engineWithTrips(tmp);

        IllegalArgumentException select = bindFailure(engine, "SELECT * FROM missing;");
        assertTrue(select.getMessage().contains("unknown table: missing"), select.getMessage());

        IllegalArgumentException copy = bindFailure(engine, "COPY missing FROM 'trips.csv';");
        assertTrue(copy.getMessage().contains("unknown table: missing"), copy.getMessage());
    }

    @Test
    void unknownColumnThrows(@TempDir Path tmp) {
        StorageEngine engine = engineWithTrips(tmp);

        IllegalArgumentException e = bindFailure(engine, "SELECT * FROM trips WHERE nope > 1;");
        assertTrue(e.getMessage().contains("unknown column: nope"), e.getMessage());
    }

    @Test
    void typeMismatchedConstantThrows(@TempDir Path tmp) {
        StorageEngine engine = engineWithTrips(tmp);

        // The case named in the exercise, plus both other directions: the
        // match is exact, so a Long constant is no DOUBLE constant either.
        IllegalArgumentException stringForLong =
                bindFailure(engine, "SELECT * FROM trips WHERE distance = 'x';");
        assertTrue(stringForLong.getMessage().contains("expected LONG got String"),
                stringForLong.getMessage());

        IllegalArgumentException longForString =
                bindFailure(engine, "SELECT * FROM trips WHERE city = 1;");
        assertTrue(longForString.getMessage().contains("expected STRING got Long"),
                longForString.getMessage());

        IllegalArgumentException longForDouble =
                bindFailure(engine, "SELECT * FROM trips WHERE price = 1;");
        assertTrue(longForDouble.getMessage().contains("expected DOUBLE got Long"),
                longForDouble.getMessage());
    }

    @Test
    void duplicateAndEmptyColumnListsThrow(@TempDir Path tmp) {
        StorageEngine engine = engineWithTrips(tmp);

        IllegalArgumentException duplicate =
                bindFailure(engine, "CREATE TABLE dup (city STRING, distance LONG, city DOUBLE);");
        assertTrue(duplicate.getMessage().contains("duplicate column name: city"),
                duplicate.getMessage());

        // The grammar demands at least one column, so an empty list is only
        // reachable by building the AST directly; the binder guards it anyway.
        Binder binder = new Binder(engine);
        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new CreateTableStatement("empty", List.of())));
        assertTrue(empty.getMessage().contains("column list must not be empty"), empty.getMessage());
    }

    // --- helpers -------------------------------------------------------

    private static StorageEngine engineWithTrips(Path dir) {
        StorageEngine engine = new StorageEngine(dir);
        engine.createTable("trips", TRIPS_SCHEMA);
        return engine;
    }

    private void bindScript(StorageEngine engine, String sql) {
        Binder binder = new Binder(engine);
        for (Statement statement : parser.parse(sql)) {
            binder.bind(statement);
        }
    }

    private IllegalArgumentException bindFailure(StorageEngine engine, String sql) {
        List<Statement> statements = parser.parse(sql);
        assertEquals(1, statements.size(), sql);
        Binder binder = new Binder(engine);
        return assertThrows(IllegalArgumentException.class, () -> binder.bind(statements.get(0)));
    }
}
