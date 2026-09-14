package dk.itu.swandb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The demo is this week's runnable evidence: started with no arguments, it
 * must print the pretty-printed form of the four Exercise 3 Task 1
 * statements, one statement per line, and execute nothing.
 */
class EngineTest {

    @Test
    void demoPrintsTheFourTask1Statements() {
        assertEquals(List.of(
                "CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);",
                "COPY trips FROM 'trips.csv';",
                "SELECT * FROM trips WHERE distance > 100;",
                "SELECT * FROM trips;"),
                demoOutputLines());
    }

    private static List<String> demoOutputLines() {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            Engine.main(new String[0]);
        } finally {
            System.setOut(originalOut);
        }
        return captured.toString(StandardCharsets.UTF_8).lines().toList();
    }
}
