package dk.itu.swandb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * On-disk JSON catalog. Stores per-table schema, the data file name, the
 * row capacity of each partition, and the per-partition min/max summaries
 * for every column. The catalog is the single source of truth for pruning
 * — no per-data-file footer or header is consulted.
 */
public final class Catalog {

    /** JSON file name inside the data directory. */
    public static final String CATALOG_FILE = "catalog.json";

    private static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    // Preserve the numeric distinction between LONG and DOUBLE columns
                    // by always deserialising bare integers as Long, not Integer.
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_LONG_FOR_INTS);

    /** One column's min/max summary for one partition. */
    public static final class ColumnSummary {
        public String name;
        public ColumnType type;
        public Object min;
        public Object max;

        public ColumnSummary() {
        }

        public ColumnSummary(String name, ColumnType type, Object min, Object max) {
            this.name = name;
            this.type = type;
            this.min = min;
            this.max = max;
        }

        public String name() { return name; }
        public ColumnType type() { return type; }
        public Object min() { return min; }
        public Object max() { return max; }
    }

    /** One partition in a data file. */
    public static final class PartitionEntry {
        public int index;
        public int rowCount;
        public List<ColumnSummary> columns;

        public PartitionEntry() {
        }

        public PartitionEntry(int index, int rowCount, List<ColumnSummary> columns) {
            this.index = index;
            this.rowCount = rowCount;
            this.columns = columns;
        }

        public int index() { return index; }
        public int rowCount() { return rowCount; }
        public List<ColumnSummary> columns() { return columns; }
    }

    /** One table's catalog entry. */
    public static final class TableEntry {
        public String name;
        public List<ColumnSpec> columns = new ArrayList<>();
        public String dataFile;
        public int maxRowsPerPartition;
        public List<PartitionEntry> partitions = new ArrayList<>();

        public TableEntry() {
        }

        public TableEntry(String name, List<ColumnSpec> columns, String dataFile,
                          int maxRowsPerPartition) {
            this.name = name;
            this.columns = new ArrayList<>(columns);
            this.dataFile = dataFile;
            this.maxRowsPerPartition = maxRowsPerPartition;
        }
    }

    /** Top-level catalog document. */
    public static final class Document {
        public Map<String, TableEntry> tables = new LinkedHashMap<>();
    }

    private final Path dataDir;
    private final Path file;
    private Document doc;

    public Catalog(Path dataDir) {
        this.dataDir = dataDir;
        this.file = dataDir.resolve(CATALOG_FILE);
        load();
    }

    private void load() {
        try {
            if (Files.exists(file)) {
                doc = MAPPER.readValue(file.toFile(), Document.class);
            } else {
                doc = new Document();
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to read catalog at " + file, e);
        }
    }

    /** Persist the in-memory document to disk. */
    public synchronized void save() {
        try {
            Files.createDirectories(dataDir);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), doc);
        } catch (IOException e) {
            throw new IllegalStateException("failed to write catalog at " + file, e);
        }
    }

    public boolean hasTable(String tableName) {
        return doc.tables.containsKey(tableName);
    }

    public TableEntry getTable(String tableName) {
        TableEntry t = doc.tables.get(tableName);
        if (t == null) {
            throw new IllegalArgumentException("unknown table: " + tableName);
        }
        return t;
    }

    public void addTable(TableEntry entry) {
        doc.tables.put(entry.name, entry);
    }
}
