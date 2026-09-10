package dk.itu.swandb;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Entry point for {@code mvn exec:java}. Loads the golden trips CSV
 * into a fresh engine rooted under a local data directory and prints the
 * three predicate results required by Exercise 2.
 *
 * The load step is skipped on subsequent runs when the {@code trips}
 * table already exists in the on-disk catalog, so the demo is
 * idempotent. Delete {@code engine-data/} to force a reload.
 */
public final class Engine {
    private static final Logger LOGGER = LoggerFactory.getLogger(Engine.class);

    public static void main(String[] args) {
        MDC.put("sessionId", UUID.randomUUID().toString());
        MDC.put("statementNumber", "0");
        LOGGER.debug("engine started");

        Path dataDir = Path.of("engine-data");
        StorageEngine engine = new StorageEngine(dataDir);

        boolean alreadyLoaded = new Catalog(dataDir).hasTable("trips");
        if (alreadyLoaded) {
            LOGGER.debug("table=trips already loaded; skipping createTable and copyFile");
        } else {
            engine.createTable("trips", List.of(
                    new ColumnSpec("city", ColumnType.STRING),
                    new ColumnSpec("distance", ColumnType.LONG),
                    new ColumnSpec("price", ColumnType.DOUBLE)));

            engine.copyFile("trips", "src/test/resources/trips.csv");
        }

        System.out.println("Predicate: distance GREATER_THAN 100");
        List<Object[]> byDistance = engine.select("trips", "distance",
                Comparison.GREATER_THAN, 100L);
        for (Object[] row : byDistance) {
            System.out.println("  " + row[0] + " " + row[1] + " " + row[2]);
        }

        System.out.println("Predicate: city EQUALS Copenhagen");
        List<Object[]> byCity = engine.select("trips", "city",
                Comparison.EQUALS, "Copenhagen");
        for (Object[] row : byCity) {
            System.out.println("  " + row[0] + " " + row[1] + " " + row[2]);
        }

        System.out.println("Predicate: price LESS_THAN 50.0");
        List<Object[]> byPrice = engine.select("trips", "price",
                Comparison.LESS_THAN, 50.0);
        for (Object[] row : byPrice) {
            System.out.println("  " + row[0] + " " + row[1] + " " + row[2]);
        }

        LOGGER.debug("engine stopped");
    }
}
