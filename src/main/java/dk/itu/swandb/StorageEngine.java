package dk.itu.swandb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Persistent storage engine for headerless CSV input. Reads/writes a
 * {@code catalog.json} plus one {@code .swan} file per table. All state
 * lives under the directory passed to the constructor, so each test
 * gets a fresh tree and a restart is just another instance.
 */
public final class StorageEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(StorageEngine.class);

    /** Default maximum rows per partition. */
    public static final int DEFAULT_MAX_ROWS_PER_PARTITION = 10_000;

    private final Path dataDir;
    private final int maxRowsPerPartition;
    private final Catalog catalog;
    private ScanStats lastScanStats;

    /** Open (or create) an engine rooted at {@code dataDirectory}. */
    public StorageEngine(Path dataDirectory) {
        this(dataDirectory, DEFAULT_MAX_ROWS_PER_PARTITION);
    }

    /**
     * Variant constructor used by tests that want to force a smaller
     * partition size than the production default.
     */
    public StorageEngine(Path dataDirectory, int maxRowsPerPartition) {
        if (maxRowsPerPartition <= 0) {
            throw new IllegalArgumentException("maxRowsPerPartition must be > 0");
        }
        this.dataDir = dataDirectory;
        this.maxRowsPerPartition = maxRowsPerPartition;
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create data dir " + dataDir, e);
        }
        this.catalog = new Catalog(dataDir);
    }

    public int maxRowsPerPartition() {
        return maxRowsPerPartition;
    }

    /** Persist a new table schema. */
    public void createTable(String tableName, List<ColumnSpec> columns) {
        long start = System.nanoTime();
        if (tableName == null || tableName.isEmpty()) {
            throw new IllegalArgumentException("table name must not be empty");
        }
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("column list must not be empty");
        }
        if (catalog.hasTable(tableName)) {
            throw new IllegalArgumentException("table already exists: " + tableName);
        }
        Set<String> seen = new HashSet<>();
        for (ColumnSpec c : columns) {
            if (!seen.add(c.name())) {
                throw new IllegalArgumentException("duplicate column name: " + c.name());
            }
        }

        String dataFile = tableName + SwanFile.EXTENSION;
        catalog.addTable(new Catalog.TableEntry(tableName, columns, dataFile, maxRowsPerPartition));
        catalog.save();

        long durationMs = (System.nanoTime() - start) / 1_000_000L;
        LOGGER.debug("table={} columns={} durationMs={}",
                tableName, columns.size(), durationMs);
    }

    /** Load a CSV file into the binary store, splitting into partitions. */
    public void copyFile(String tableName, String csvFilePath) {
        long start = System.nanoTime();
        Catalog.TableEntry table = catalog.getTable(tableName);
        if (table.partitions != null && !table.partitions.isEmpty()) {
            throw new UnsupportedOperationException(
                    "table " + tableName + " already has data; appending not supported");
        }

        Path csvPath = Path.of(csvFilePath);
        List<Object[]> rows;
        try {
            rows = CsvParser.read(csvPath, table.columns);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + csvPath, e);
        }

        List<SwanFile.Partition> partitions = new ArrayList<>();
        List<Catalog.PartitionEntry> partEntries = new ArrayList<>();
        int totalRows = rows.size();
        int partitionCap = table.maxRowsPerPartition;

        for (int offset = 0; offset < totalRows; offset += partitionCap) {
            int end = Math.min(offset + partitionCap, totalRows);
            int partitionIndex = partitions.size();
            SwanFile.Partition part = buildPartition(table.columns, rows.subList(offset, end));
            partitions.add(part);

            List<Catalog.ColumnSummary> summaries = new ArrayList<>();
            for (int c = 0; c < table.columns.size(); c++) {
                ColumnSpec spec = table.columns.get(c);
                MinMax.Result mm = MinMax.compute(spec.type(), part.columnValues.get(c));
                summaries.add(new Catalog.ColumnSummary(spec.name(), spec.type(), mm.min(), mm.max()));
                LOGGER.debug("table={} partition={} column={} min={} max={}",
                        tableName, partitionIndex, spec.name(), mm.min(), mm.max());
            }
            partEntries.add(new Catalog.PartitionEntry(partitionIndex, end - offset, summaries));
        }

        Path dataPath = dataDir.resolve(table.dataFile);
        try {
            SwanFile.write(dataPath, table.columns, partitions);
        } catch (IOException e) {
            throw new IllegalStateException("failed to write " + dataPath, e);
        }

        table.partitions = partEntries;
        catalog.save();

        long durationMs = (System.nanoTime() - start) / 1_000_000L;
        LOGGER.debug("table={} file={} rows={} partitions={} durationMs={}",
                tableName, csvFilePath, totalRows, partitions.size(), durationMs);
    }

    private static SwanFile.Partition buildPartition(List<ColumnSpec> schema, List<Object[]> rows) {
        List<List<Object>> cols = new ArrayList<>(schema.size());
        for (int c = 0; c < schema.size(); c++) {
            List<Object> values = new ArrayList<>(rows.size());
            for (Object[] row : rows) {
                values.add(row[c]);
            }
            cols.add(values);
        }
        return new SwanFile.Partition(rows.size(), cols);
    }

    /** Filtered scan over the binary store. */
    public List<Object[]> select(String tableName, String columnName,
                                 Comparison comparison, Object constant) {
        long start = System.nanoTime();
        Catalog.TableEntry table = catalog.getTable(tableName);

        int columnIndex = -1;
        ColumnSpec filterCol = null;
        for (int i = 0; i < table.columns.size(); i++) {
            if (table.columns.get(i).name().equals(columnName)) {
                columnIndex = i;
                filterCol = table.columns.get(i);
                break;
            }
        }
        if (filterCol == null) {
            throw new IllegalArgumentException("unknown column: " + columnName);
        }
        validateConstant(filterCol, constant);

        List<Object[]> out = new ArrayList<>();
        int total = table.partitions.size();
        int pruned = 0;
        int read = 0;

        if (total == 0) {
            long durationMs = (System.nanoTime() - start) / 1_000_000L;
            lastScanStats = new ScanStats(0, 0, 0);
            LOGGER.debug("table={} column={} comparison={} const={} partitionsTotal=0 partitionsRead=0 partitionsPruned=0 rowsOut=0 durationMs={}",
                    tableName, columnName, comparison, constant, durationMs);
            return out;
        }

        Path dataPath = dataDir.resolve(table.dataFile);

        for (Catalog.PartitionEntry pe : table.partitions) {
            Catalog.ColumnSummary sum = null;
            for (Catalog.ColumnSummary s : pe.columns) {
                if (s.name().equals(columnName)) {
                    sum = s;
                    break;
                }
            }
            // Filter by catalog min/max first; only hit disk when we must.
            boolean canPrune = Pruning.canPrune(comparison, constant,
                    sum == null ? null : sum.min(),
                    sum == null ? null : sum.max(),
                    filterCol.type());
            if (canPrune) {
                pruned++;
                LOGGER.debug("table={} column={} comparison={} const={} partition={} min={} max={} decision={}",
                        tableName, columnName, comparison, constant, pe.index(),
                        sum == null ? null : sum.min(),
                        sum == null ? null : sum.max(),
                        "PRUNED");
                continue;
            }
            read++;
            LOGGER.debug("table={} column={} comparison={} const={} partition={} min={} max={} decision={}",
                    tableName, columnName, comparison, constant, pe.index(),
                    sum == null ? null : sum.min(),
                    sum == null ? null : sum.max(),
                    "READ");

            SwanFile.Partition part;
            try {
                // Per-call full read is simple; the format also supports
                // seeking straight to a single partition.
                List<SwanFile.Partition> all = SwanFile.readAll(dataPath, table.columns);
                part = all.get(pe.index());
            } catch (IOException e) {
                throw new IllegalStateException("failed to read " + dataPath, e);
            }

            List<Object> filterValues = part.columnValues.get(columnIndex);
            for (int r = 0; r < part.rowCount; r++) {
                Object v = filterValues.get(r);
                if (matches(comparison, filterCol.type(), constant, v)) {
                    Object[] row = new Object[table.columns.size()];
                    for (int c = 0; c < table.columns.size(); c++) {
                        row[c] = part.columnValues.get(c).get(r);
                    }
                    out.add(row);
                }
            }
        }

        long durationMs = (System.nanoTime() - start) / 1_000_000L;
        lastScanStats = new ScanStats(total, read, pruned);
        LOGGER.debug("table={} column={} comparison={} const={} partitionsRead={} partitionsPruned={} rowsOut={} durationMs={}",
                tableName, columnName, comparison, constant, read, pruned, out.size(), durationMs);
        return out;
    }

    /** Stats from the most recent {@link #select} call. */
    public ScanStats lastScanStats() {
        if (lastScanStats == null) {
            throw new IllegalStateException("no select has been executed yet");
        }
        return lastScanStats;
    }

    private static void validateConstant(ColumnSpec col, Object constant) {
        if (constant == null) {
            throw new IllegalArgumentException("constant must not be null");
        }
        boolean ok = switch (col.type()) {
            case STRING -> constant instanceof String;
            case LONG -> constant instanceof Long;
            case DOUBLE -> constant instanceof Double;
        };
        if (!ok) {
            throw new IllegalArgumentException(
                    "constant type mismatch for column " + col.name()
                            + ": expected " + col.type() + " got " + constant.getClass().getSimpleName());
        }
    }

    private static boolean matches(Comparison comparison, ColumnType type,
                                   Object constant, Object value) {
        int cmp = MinMax.compare(type, constant, value);
        return switch (comparison) {
            case EQUALS -> cmp == 0;
            case LESS_THAN -> cmp > 0;
            case GREATER_THAN -> cmp < 0;
        };
    }
}
