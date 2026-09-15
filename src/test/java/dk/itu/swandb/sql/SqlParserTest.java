package dk.itu.swandb.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dk.itu.swandb.ColumnSpec;
import dk.itu.swandb.enums.ColumnType;
import dk.itu.swandb.enums.Comparison;
import dk.itu.swandb.sql.ast.CopyStatement;
import dk.itu.swandb.sql.ast.CreateTableStatement;
import dk.itu.swandb.sql.ast.Predicate;
import dk.itu.swandb.sql.ast.SelectStatement;
import dk.itu.swandb.sql.ast.Statement;

/**
 * Unit tests for the SQL front end: each statement shape becomes the
 * expected AST, literals carry the Java type the engine demands, keywords
 * are case-insensitive while identifier case survives, and a malformed
 * script fails with its exact position.
 */
class SqlParserTest {

    private static final String TRIPS_SCRIPT = """
            CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
            COPY trips FROM 'trips.csv';
            SELECT * FROM trips;
            """;

    private final SqlParser parser = new SqlParser();

    // --- 1. every statement shape -------------------------------------

    @Test
    void createTableParsesToExpectedAst() {
        List<Statement> statements = parser.parse(
                "CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);");

        assertEquals(List.of(new CreateTableStatement("trips", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("distance", ColumnType.LONG),
                new ColumnSpec("price", ColumnType.DOUBLE)))), statements);
    }

    @Test
    void copyParsesToExpectedAst() {
        List<Statement> statements = parser.parse("COPY trips FROM 'trips.csv';");

        assertEquals(List.of(new CopyStatement("trips", "trips.csv")), statements);
    }

    @Test
    void selectWithWhereParsesToExpectedAst() {
        List<Statement> statements = parser.parse("SELECT * FROM trips WHERE distance > 100;");

        assertEquals(List.of(new SelectStatement("trips",
                Optional.of(new Predicate("distance", Comparison.GREATER_THAN, 100L)))), statements);
    }

    @Test
    void selectWithoutWhereParsesToExpectedAst() {
        List<Statement> statements = parser.parse("SELECT * FROM trips;");

        assertEquals(List.of(new SelectStatement("trips", Optional.empty())), statements);
    }

    @Test
    void everyComparisonOperatorParses() {
        assertEquals(Comparison.EQUALS, predicateOf("SELECT * FROM trips WHERE city = 'Odense';").comparison());
        assertEquals(Comparison.LESS_THAN, predicateOf("SELECT * FROM trips WHERE distance < 100;").comparison());
        assertEquals(Comparison.GREATER_THAN, predicateOf("SELECT * FROM trips WHERE distance > 100;").comparison());
    }

    @Test
    void aScriptYieldsAllItsStatementsInOrder() {
        assertEquals(List.of(
                new CreateTableStatement("trips", List.of(
                        new ColumnSpec("city", ColumnType.STRING),
                        new ColumnSpec("distance", ColumnType.LONG),
                        new ColumnSpec("price", ColumnType.DOUBLE))),
                new CopyStatement("trips", "trips.csv"),
                new SelectStatement("trips", Optional.empty())),
                parser.parse(TRIPS_SCRIPT));
    }

    // --- 2. literal typing --------------------------------------------

    @Test
    void literalsCarryTheJavaTypeTheEngineDemands() {
        assertEquals(12L, constantOf("SELECT * FROM trips WHERE distance > 12;"));
        assertEquals(12.0, constantOf("SELECT * FROM trips WHERE price > 12.0;"));
        assertEquals("12", constantOf("SELECT * FROM trips WHERE city > '12';"));

        // Not just the value: the exact class decides whether the engine
        // accepts the constant (an Integer is not a LONG constant).
        assertEquals(Long.class, constantOf("SELECT * FROM trips WHERE distance > 12;").getClass());
        assertEquals(Double.class, constantOf("SELECT * FROM trips WHERE price > 12.0;").getClass());
        assertEquals(String.class, constantOf("SELECT * FROM trips WHERE city > '12';").getClass());
    }

    @Test
    void negativeLiteralsAreTyped() {
        assertEquals(-1L, constantOf("SELECT * FROM trips WHERE distance > -1;"));
        assertEquals(-1.5, constantOf("SELECT * FROM trips WHERE price > -1.5;"));

        assertEquals(Long.class, constantOf("SELECT * FROM trips WHERE distance > -1;").getClass());
        assertEquals(Double.class, constantOf("SELECT * FROM trips WHERE price > -1.5;").getClass());
    }

    @Test
    void aQuotedNumberIsAString() {
        assertEquals("12", constantOf("SELECT * FROM trips WHERE city = '12';"));
    }

    // --- 3. case-insensitivity -----------------------------------------

    @Test
    void keywordsAreCaseInsensitiveAndIdentifierCaseSurvives() {
        assertEquals(List.of(new SelectStatement("Trips", Optional.empty())),
                parser.parse("select * from Trips ;"));

        // Type names are keywords too, so 'string' is STRING; the column name
        // keeps the casing it was written with.
        assertEquals(List.of(new CreateTableStatement("MyTable", List.of(
                new ColumnSpec("City", ColumnType.STRING), new ColumnSpec("_id2", ColumnType.LONG)))),
                parser.parse("CrEaTe TaBlE MyTable ( City string , _id2 Long ) ;"));
    }

    @Test
    void whereClauseKeywordsAreCaseInsensitive() {
        assertEquals(new Predicate("distance", Comparison.GREATER_THAN, 100L),
                predicateOf("select * from trips where distance > 100;"));
    }

    // --- 4. malformed input --------------------------------------------

    @Test
    void missingSemicolonIsReportedAtEndOfInput() {
        SqlParseException e = parseFailure("SELECT * FROM trips");

        assertEquals(1, e.line());
        assertEquals(19, e.column());
        assertTrue(e.getMessage().contains("missing ';'"), e.getMessage());
    }

    @Test
    void unbalancedParenthesisIsReportedAtTheOffendingToken() {
        SqlParseException e = parseFailure("CREATE TABLE trips (city STRING, distance LONG;");

        assertEquals(1, e.line());
        assertEquals(46, e.column());
    }

    @Test
    void unknownTypeNameIsReported() {
        SqlParseException e = parseFailure("CREATE TABLE trips (city TEXT);");

        assertEquals(1, e.line());
        assertEquals(25, e.column());
        assertTrue(e.getMessage().contains("TEXT"), e.getMessage());
    }

    @Test
    void unterminatedStringLiteralIsReportedAtItsOpeningQuote() {
        SqlParseException e = parseFailure("COPY trips FROM 'trips.csv;");

        assertEquals(1, e.line());
        assertEquals(16, e.column());
    }

    @Test
    void missingFromIsReported() {
        SqlParseException e = parseFailure("SELECT * trips;");

        assertEquals(1, e.line());
        assertEquals(9, e.column());
        assertTrue(e.getMessage().contains("FROM"), e.getMessage());
    }

    @Test
    void lineNumbersCountAcrossStatements() {
        // Line 2, column 9 is 'trips'; a position counted over the whole
        // script would be 34, so this pins the line-relative convention.
        SqlParseException e = parseFailure(
                "CREATE TABLE trips (city STRING);\nSELECT * trips;");

        assertEquals(2, e.line());
        assertEquals(9, e.column());
    }

    // --- 5. comments and whitespace ------------------------------------

    @Test
    void commentsAndWhitespaceAreSkipped() {
        List<Statement> statements = parser.parse("""
                -- the trips table, loaded from CSV
                CREATE   TABLE trips ( city  STRING ,
                    distance LONG , price DOUBLE ) ;   -- schema done

                    COPY trips FROM 'trips.csv';
                """);

        assertEquals(List.of(
                new CreateTableStatement("trips", List.of(
                        new ColumnSpec("city", ColumnType.STRING),
                        new ColumnSpec("distance", ColumnType.LONG),
                        new ColumnSpec("price", ColumnType.DOUBLE))),
                new CopyStatement("trips", "trips.csv")),
                statements);
    }

    @Test
    void commentBeforeTheSemicolonIsSkipped() {
        assertEquals(List.of(new SelectStatement("trips", Optional.empty())),
                parser.parse("SELECT * FROM trips -- every row\n;"));
    }

    // --- helpers -------------------------------------------------------

    private SqlParseException parseFailure(String sql) {
        return assertThrows(SqlParseException.class, () -> parser.parse(sql), sql);
    }

    private Predicate predicateOf(String sql) {
        List<Statement> statements = parser.parse(sql);
        SelectStatement select = assertInstanceOf(SelectStatement.class, statements.get(0), sql);
        return select.where().orElseThrow(() -> new AssertionError("no WHERE in " + sql));
    }

    private Object constantOf(String sql) {
        return predicateOf(sql).constant();
    }
}
