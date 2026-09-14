package dk.itu.swandb;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import dk.itu.swandb.sql.SqlParser;
import dk.itu.swandb.sql.SqlPrinter;
import dk.itu.swandb.sql.ast.Statement;

/**
 * Entry point for {@code mvn exec:java}. Parses the four statements of
 * Exercise 3 Task 1 and prints their pretty-printed form, one statement per
 * line. Nothing is executed: the pretty-printer renders from the AST, so
 * the output is evidence that the parse captured the whole statement.
 * Wiring the AST to the storage engine is week 4's work.
 */
public final class Engine {
    private static final Logger LOGGER = LoggerFactory.getLogger(Engine.class);

    /** The Task 1 script: the whole SQL subset, as DuckDB would accept it. */
    private static final String DEMO_SCRIPT = """
            CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
            COPY trips FROM 'trips.csv';
            SELECT * FROM trips WHERE distance > 100;
            SELECT * FROM trips;
            """;

    public static void main(String[] args) {
        MDC.put("sessionId", UUID.randomUUID().toString());
        MDC.put("statementNumber", "0");
        try {
            LOGGER.debug("engine started");

            List<Statement> statements = new SqlParser().parse(DEMO_SCRIPT);
            SqlPrinter printer = new SqlPrinter();
            for (Statement statement : statements) {
                System.out.println(printer.print(statement));
            }

            LOGGER.debug("engine stopped");
        } finally {
            MDC.remove("statementNumber");
            MDC.remove("sessionId");
        }
    }
}
