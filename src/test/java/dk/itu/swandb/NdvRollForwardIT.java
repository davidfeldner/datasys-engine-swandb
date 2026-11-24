package dk.itu.swandb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dk.itu.swandb.enums.ColumnType;
import dk.itu.swandb.enums.Comparison;

/**
 * The design document's roll-forward step: a table copied before Exercise 11
 * has no NDV in its catalog, and the first statement that opens it fills the
 * statistic in, once and persistently.
 */
class NdvRollForwardIT {

    private static final List<ColumnSpec> TRIPS = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    @Test
    void aLegacyCatalogGetsItsNdvOnTheFirstQuery(@TempDir Path tmp) throws Exception {
        Path csv = Files.writeString(tmp.resolve("trips.csv"), """
                Copenhagen,12,23.5
                Aarhus,187,301.0
                Copenhagen,95,120.75
                Odense,140,210.0
                """);
        try (StorageEngine engine = new StorageEngine(tmp, 2)) {
            engine.createTable("trips", TRIPS);
            engine.copyFile("trips", csv.toString());
        }

        // Rewrite the catalog as an Exercise 10 engine left it: every column
        // summary without an ndv field.
        stripNdv(tmp.resolve("catalog.json"));
        assertNoNdv(tmp.resolve("catalog.json"));

        // The first statement that opens the table fills the statistic in...
        try (StorageEngine legacy = new StorageEngine(tmp)) {
            assertEquals(4, legacy.select("trips", "distance", Comparison.GREATER_THAN, 0L).size(),
                    "the legacy table still returns all its rows");
            legacy.close();
        }

        // ...and it is persisted, so the next engine reads it from the catalog.
        assertEquals(2L, ndv(tmp.resolve("catalog.json"), "trips", 0, "city"),
                "partition 0 holds Copenhagen twice, so one distinct city");
        assertEquals(2L, ndv(tmp.resolve("catalog.json"), "trips", 1, "city"),
                "partition 1 holds Odense then a repeat, so two distinct cities in the file");
    }

    @Test
    void aFullyPopulatedCatalogIsNotRewritten(@TempDir Path tmp) throws Exception {
        Path csv = Files.writeString(tmp.resolve("trips.csv"), "Copenhagen,12,23.5\nAarhus,187,301.0\n");
        try (StorageEngine engine = new StorageEngine(tmp, 10)) {
            engine.createTable("trips", TRIPS);
            engine.copyFile("trips", csv.toString());
        }
        int version = new StorageEngine(tmp).version("trips");

        try (StorageEngine engine = new StorageEngine(tmp)) {
            engine.select("trips", "distance", Comparison.GREATER_THAN, 0L);
            assertEquals(version, engine.version("trips"),
                    "a catalog that already has NDV needs no roll-forward commit");
        }
    }

    @Test
    void aMissingNdvIsLoggedAsItIsFilled(@TempDir Path tmp) throws Exception {
        // A unique table name makes the run's log lines unambiguous.
        String tag = java.util.UUID.randomUUID().toString().replace("-", "");
        String table = "legacy" + tag;
        Path csv = Files.writeString(tmp.resolve("trips.csv"), "Copenhagen,12,23.5\nCopenhagen,13,24.5\n");
        try (StorageEngine engine = new StorageEngine(tmp, 10)) {
            engine.createTable(table, TRIPS);
            engine.copyFile(table, csv.toString());
        }
        Path catalog = tmp.resolve("catalog.json");
        stripNdv(catalog);
        assertNull(ndv(catalog, table, 0, "city"), "the fixture starts without NDV");

        try (StorageEngine legacy = new StorageEngine(tmp)) {
            legacy.select(table, "distance", Comparison.GREATER_THAN, 0L);
        }

        List<String> lines = Files.readAllLines(Path.of("logs", "engine.log")).stream()
                .filter(line -> line.contains(table))
                .filter(line -> line.contains("column=city"))
                .toList();
        assertTrue(lines.stream().anyMatch(l -> l.contains("ndv=1")),
                "the filled NDV is logged as it is created: " + lines);
    }

    // --- helpers -------------------------------------------------------

    /** Removes every {@code ndv} field, as a pre-Exercise-11 catalog lacks it. */
    private static void stripNdv(Path catalog) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(catalog.toFile());
        ObjectNode tables = (ObjectNode) root.get("tables");
        tables.properties().forEach(table -> {
            ArrayNode partitions = (ArrayNode) table.getValue().get("partitions");
            for (var partition : partitions) {
                for (var summary : (ArrayNode) partition.get("columns")) {
                    ((ObjectNode) summary).remove("ndv");
                }
            }
        });
        Files.writeString(catalog, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }

    private static void assertNoNdv(Path catalog) throws Exception {
        for (int partition = 0; partition < 2; partition++) {
            assertNull(ndv(catalog, "trips", partition, "city"),
                    "the fixture must start without NDV, partition " + partition);
        }
    }

    private static Long ndv(Path catalog, String table, int partition, String column) throws Exception {
        var root = new ObjectMapper().readTree(catalog.toFile());
        var summaries = root.get("tables").get(table).get("partitions").get(partition).get("columns");
        for (var summary : summaries) {
            if (summary.get("name").asText().equals(column)) {
                var value = summary.get("ndv");
                if (value == null || value.isNull()) return null;
                return value.asLong();
            }
        }
        throw new AssertionError("no summary for column " + column);
    }

    @Test
    void theFixtureIsWhatTheHelpersAssume(@TempDir Path tmp) throws Exception {
        // Guards the two helpers above against silently matching nothing.
        Path catalog = tmp.resolve("catalog.json");
        Files.writeString(catalog, """
                {"tables":{"trips":{"partitions":[
                  {"columns":[{"name":"city","type":"STRING","min":"A","max":"B"}]},
                  {"columns":[{"name":"city","type":"STRING","min":"A","max":"B"}]}
                ]}}}
                """);
        assertNull(ndv(catalog, "trips", 0, "city"));
        assertNotNull(catalog);
    }
}
