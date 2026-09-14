package dk.itu.swandb.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dk.itu.swandb.enums.Comparison;
import dk.itu.swandb.sql.ast.CreateTableStatement;
import dk.itu.swandb.sql.ast.Predicate;
import dk.itu.swandb.sql.ast.SelectStatement;
import dk.itu.swandb.sql.ast.Statement;

/**
 * Unit tests for the pretty-printer. The property that matters is the
 * round trip: whatever the printer emits must parse back to an equal AST,
 * which is only possible if the AST captured the statement completely.
 */
class SqlPrinterTest {

    /** The four statements of Exercise 3 Task 1, in their normalized form. */
    private static final String TASK_1_SCRIPT = """
            CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
            COPY trips FROM 'trips.csv';
            SELECT * FROM trips WHERE distance > 100;
            SELECT * FROM trips;
            """;

    private final SqlParser parser = new SqlParser();
    private final SqlPrinter printer = new SqlPrinter();

    @Test
    void everyStatementShapeRoundTrips() {
        List<Statement> statements = parser.parse(TASK_1_SCRIPT);
        assertEquals(4, statements.size());
        for (Statement statement : statements) {
            assertRoundTrips(statement);
        }
    }

    @Test
    void everyLiteralTypeRoundTrips() {
        assertRoundTrips(one("SELECT * FROM trips WHERE distance > 12;"));
        assertRoundTrips(one("SELECT * FROM trips WHERE price > 12.0;"));
        assertRoundTrips(one("SELECT * FROM trips WHERE city > '12';"));
        assertRoundTrips(one("SELECT * FROM trips WHERE distance > -1;"));
        assertRoundTrips(one("SELECT * FROM trips WHERE price > -1.5;"));
        assertRoundTrips(one("SELECT * FROM trips WHERE city = 'Copenhagen';"));
        // Punctuation inside a literal is data, not syntax.
        assertRoundTrips(one("SELECT * FROM trips WHERE city = 'New York; -- still one string';"));
        assertRoundTrips(one("SELECT * FROM trips WHERE city = '';"));
    }

    @Test
    void doublesOutsideDoubleToStringsPlainRangeRoundTrip() {
        // Double.toString(1.0E10) is scientific notation, which the literal
        // rule does not match, and a plain '10000000000' would lex as a LONG.
        assertRoundTrips(one("SELECT * FROM trips WHERE price > 10000000000.0;"));
        assertRoundTrips(one("SELECT * FROM trips WHERE price > 0.00001;"));
        assertRoundTrips(one("SELECT * FROM trips WHERE price > -10000000000.5;"));
    }

    @Test
    void printingNormalizesKeywordsTypeNamesAndCase() {
        assertEquals("SELECT * FROM trips;",
                printer.print(one("select * from trips -- every row\n;")));
        assertEquals("CREATE TABLE MyTable (City STRING, Distance LONG);",
                printer.print(one("create  table MyTable ( City string , Distance long ) ;")));
        assertEquals("SELECT * FROM Trips WHERE City = 'Odense';",
                printer.print(one("SELECT * FROM Trips WHERE City = 'Odense';")));
    }

    @Test
    void printingTheTask1ScriptGivesBackTheTask1Text() {
        List<String> printed = parser.parse(TASK_1_SCRIPT).stream().map(printer::print).toList();

        assertEquals(TASK_1_SCRIPT.lines().toList(), printed);
    }

    @Test
    void aScriptReprintsAsAWholeScript() {
        // The round trip holds for a script, not only for single statements:
        // every printed statement carries its ';', so the concatenation is
        // itself a valid script that parses to the same statements.
        List<Statement> statements = parser.parse("""
                select * from Trips where distance > -1 ;
                COPY Trips FROM 'trips.csv';
                """);

        String reprinted = statements.stream().map(printer::print).reduce("", String::concat);

        assertEquals("SELECT * FROM Trips WHERE distance > -1;COPY Trips FROM 'trips.csv';",
                reprinted);
        assertEquals(statements, parser.parse(reprinted));
    }

    @Test
    void aTableWithoutColumnsIsRefusedInsteadOfPrintedWrong() {
        // The grammar demands at least one column, so 'CREATE TABLE x ();'
        // would parse nowhere: better to refuse than to emit invalid SQL.
        CreateTableStatement noColumns = new CreateTableStatement("empty", List.of());

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> printer.print(noColumns));

        assertTrue(e.getMessage().contains("at least one column"), e.getMessage());
    }

    @Test
    void stringConstantsTheGrammarCannotReadBackAreRefused() {
        // Escaped quotes are out of scope and a literal ends at the next quote
        // on the same line, so these values have no spelling in the subset.
        for (String value : List.of("it's", "two\nlines", "carriage\rreturn")) {
            SelectStatement select = new SelectStatement("trips",
                    Optional.of(new Predicate("city", Comparison.EQUALS, value)));

            IllegalArgumentException e =
                    assertThrows(IllegalArgumentException.class, () -> printer.print(select), value);

            assertTrue(e.getMessage().contains("cannot print string constant"), e.getMessage());
        }
    }

    @Test
    void doublesThatAreNotFiniteAreRefused() {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY}) {
            SelectStatement select = new SelectStatement("trips",
                    Optional.of(new Predicate("price", Comparison.LESS_THAN, value)));

            assertThrows(IllegalArgumentException.class, () -> printer.print(select));
        }
    }

    /** print(s) parses back to a statement equal to s. */
    private void assertRoundTrips(Statement statement) {
        String printed = printer.print(statement);
        assertEquals(List.of(statement), parser.parse(printed),
                "round trip failed for printed form: " + printed);
    }

    private Statement one(String sql) {
        List<Statement> statements = parser.parse(sql);
        assertEquals(1, statements.size(), sql);
        return statements.get(0);
    }
}
