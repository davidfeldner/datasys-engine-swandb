package dk.itu.swandb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import dk.itu.swandb.enums.ColumnType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * On-disk JSON catalog. Stores per-table schema, the data file name, the
 * row capacity of each partition, and the per-partition min/max summaries
 * for every column. The catalog is the single source of truth for pruning
 * — no per-data-file footer or header is consulted.
 *
 * <p>Since Exercise 7 a table also carries its committed version, and each
 * partition records the version it was written in. The on-disk catalog is
 * the <em>newest</em> version's partition list (every table has one data
 * file, so older versions' partition lists point into bytes that a later
 * commit may have truncated away); the engine therefore keeps the older
 * versions in memory (see {@link VersionedTable}) and writes them nowhere.
 *
 * <p>{@link #save()} writes a temporary file and renames it over
 * {@code catalog.json}: on POSIX {@code rename} replaces the target in one
 * step, so a reader or a crash sees either the old catalog or the new one,
 * never half of each.
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

        public ColumnSummary copy() { return new ColumnSummary(name, type, min, max); }
    }

    /** One partition in a data file. */
    public static final class PartitionEntry {
        /** Position in this version's partition list; not the position in the file. */
        public int index;
        public int rowCount;
        /** The version that wrote these rows, and whose summary they carry. */
        public int version;
        public List<ColumnSummary> columns;
        /**
         * Byte offset of this partition in the {@code .swan} file. Defaults
         * to -1, which is also what a catalog written before offset tracking
         * deserialises to; the reader then falls back to a sequential scan.
         */
        public long offset = -1;
        /**
         * Byte just past this partition in the file. Recorded when written so
         * a commit can truncate the file back to the last live partition
         * without reading it; -1 for catalogs written before it existed.
         */
        public long endOffset = -1;

        public PartitionEntry() {
        }

        public PartitionEntry(int index, int rowCount, List<ColumnSummary> columns) {
            this.index = index;
            this.rowCount = rowCount;
            this.columns = columns;
        }

        public int index() { return index; }
        public int version() { return version; }
        public int rowCount() { return rowCount; }
        public List<ColumnSummary> columns() { return columns; }
        public long offset() { return offset; }

        /**
         * The byte just past this partition in the file. Catalogs written
         * before {@code endOffset} existed answer -1; their partitions are
         * re-measured by the commit that needs the boundary.
         */
        public long endOffset() { return endOffset; }

        /**
         * A detached copy. Versions share entries — a carried partition is
         * the very object the old version lists — so no version ever edits an
         * entry in place; a commit copies what it changes.
         */
        public PartitionEntry copy() {
            List<ColumnSummary> copied = new ArrayList<>(columns.size());
            for (ColumnSummary c : columns) {
                copied.add(c.copy());
            }
            PartitionEntry out = new PartitionEntry(index, rowCount, copied);
            out.version = version;
            out.offset = offset;
            out.endOffset = endOffset;
            return out;
        }
    }

    /** One table's catalog entry: its schema, its data file and one version's partitions. */
    public static final class TableEntry {
        public String name;
        public List<ColumnSpec> columns = new ArrayList<>();
        /** The table's newest committed version. */
        public int version;
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
            if (Files.exists(file)) doc = MAPPER.readValue(file.toFile(), Document.class);
            else doc = new Document();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read catalog at " + file, e);
        }
    }

    /** Where {@code catalog.json} lives. */
    public Path file() { return file; }

    /**
     * Persist the in-memory document to disk, atomically: write
     * {@code catalog.json.tmp}, then rename it over {@code catalog.json}.
     */
    public synchronized void save() {
        Path tmp = dataDir.resolve(CATALOG_FILE + ".tmp");
        try {
            Files.createDirectories(dataDir);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), doc);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                // Not every filesystem supports the atomic variant (some
                // network mounts); a plain replace is still one move.
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to write catalog at " + file, e);
        }
    }

    public boolean hasTable(String tableName) {
        return doc.tables.containsKey(tableName);
    }

    public TableEntry getTable(String tableName) {
        TableEntry t = doc.tables.get(tableName);
        if (t == null) throw new IllegalArgumentException("unknown table: " + tableName);
        return t;
    }

    public List<TableEntry> tables() {
        return new ArrayList<>(doc.tables.values());
    }

    public void addTable(TableEntry entry) {
        doc.tables.put(entry.name, entry);
    }
}
