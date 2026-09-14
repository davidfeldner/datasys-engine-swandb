package dk.itu.swandb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Persistent storage engine for headerless CSV input. Reads/writes a
 * {@code catalog.json} plus one {@code .swan} file per table. All state
 * lives under the directory passed to the constructor, so each test
 * gets a fresh tree and a restart is just another instance.
 *
 * <p>Every public call emits at least one CSV log line — a summary line on
 * success, or a {@code status=FAILED} line when the call throws — so the
 * log is a complete record of API usage. All values that reach a log
 * message go through {@link LogSafe} so a line always parses into exactly
 * seven comma-delimited values.
 */
public final class StorageEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(StorageEngine.class);

    /** Default maximum rows per partition. */
    static final int DEFAULT_MAX_ROWS_PER_PARTITION = 10_000;

    /** {@code statementNumber} for this week's Java API: there is no SQL text yet. */
    private static final String DEFAULT_STATEMENT_NUMBER = "0";

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
        if (maxRowsPerPartition <= 0) throw new IllegalArgumentException("maxRowsPerPartition must be > 0");
        this.dataDir = dataDirectory;
        this.maxRowsPerPartition = maxRowsPerPartition;
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create data dir " + dataDir, e);
        }
        this.catalog = new Catalog(dataDir);
    }

    int maxRowsPerPartition() {
        return maxRowsPerPartition;
    }

    /**
     * Populate the MDC slots that the CSV log layout reads, so a line is
     * well formed even when the storage API is driven directly instead of
     * through {@code Engine.main}. Existing values are left alone, which
     * keeps a caller-supplied session intact.
     */
    private static void ensureLogContext() {
        if (MDC.get("sessionId") == null) MDC.put("sessionId", UUID.randomUUID().toString());
        if (MDC.get("statementNumber") == null) MDC.put("statementNumber", DEFAULT_STATEMENT_NUMBER);
    }

    /** Begin a call: guarantees log context, returns a start timestamp. */
    private static long beginCall() {
        ensureLogContext();
        return System.nanoTime();
    }

    private static long elapsedMs(long start) {
        return (System.nanoTime() - start) / 1_000_000L;
    }

    /**
     * Log the failure line for a call that threw, so every call has at
     * least one log line whether it succeeded or not.
     */
    private static void logFailure(String operation, String keys, long start, RuntimeException e) {
        LOGGER.debug("operation={} {} status=FAILED error={} durationMs={}",
                operation, keys, LogSafe.value(e.getMessage()), elapsedMs(start));
    }

    /** Persist a new table schema. */
    public void createTable(String tableName, List<ColumnSpec> columns) {
        long start = beginCall();
        try {
            if (tableName == null || tableName.isEmpty()) throw new IllegalArgumentException("table name must not be empty");
            if (columns == null || columns.isEmpty()) throw new IllegalArgumentException("column list must not be empty");
            if (catalog.hasTable(tableName)) throw new IllegalArgumentException("table already exists: " + tableName);
            Set<String> seen = new HashSet<>();
            for (ColumnSpec c : columns) {
                if (!seen.add(c.name())) throw new IllegalArgumentException("duplicate column name: " + c.name());
            }

            String dataFile = tableName + SwanFile.EXTENSION;
            catalog.addTable(new Catalog.TableEntry(tableName, columns, dataFile, maxRowsPerPartition));
            catalog.save();

            LOGGER.debug("table={} columns={} durationMs={}",
                    LogSafe.value(tableName), columns.size(), elapsedMs(start));
        } catch (RuntimeException e) {
            logFailure("createTable", "table=" + LogSafe.value(tableName), start, e);
            throw e;
        }
    }

    /** Load a CSV file into the binary store, splitting into partitions. */
    public void copyFile(String tableName, String csvFilePath) {
        long start = beginCall();
        try {
            Catalog.TableEntry table = catalog.getTable(tableName);
            if (table.partitions != null && !table.partitions.isEmpty())
                throw new UnsupportedOperationException(
                        "table " + tableName + " already has data; appending not supported");

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
                            LogSafe.value(tableName), partitionIndex, LogSafe.value(spec.name()),
                            LogSafe.value(mm.min()), LogSafe.value(mm.max()));
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

            LOGGER.debug("table={} file={} rows={} partitions={} durationMs={}",
                    LogSafe.value(tableName), LogSafe.value(csvFilePath), totalRows,
                    partitions.size(), elapsedMs(start));
        } catch (RuntimeException e) {
            logFailure("copyFile",
                    "table=" + LogSafe.value(tableName) + " file=" + LogSafe.value(csvFilePath), start, e);
            throw e;
        }
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
        long start = beginCall();
        try {
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
            if (filterCol == null) throw new IllegalArgumentException("unknown column: " + columnName);
            validateConstant(filterCol, constant);

            List<Object[]> out = new ArrayList<>();
            int total = table.partitions.size();
            int pruned = 0;
            int read = 0;

            if (total == 0) {
                lastScanStats = new ScanStats(0, 0, 0);
                LOGGER.debug("table={} column={} comparison={} const={} partitionsTotal=0 partitionsRead=0 partitionsPruned=0 rowsOut=0 durationMs={}",
                        LogSafe.value(tableName), LogSafe.value(columnName), LogSafe.value(comparison),
                        LogSafe.value(constant), elapsedMs(start));
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
                            LogSafe.value(tableName), LogSafe.value(columnName), LogSafe.value(comparison),
                            LogSafe.value(constant), pe.index(),
                            LogSafe.value(sum == null ? null : sum.min()),
                            LogSafe.value(sum == null ? null : sum.max()),
                            "PRUNED");
                    continue;
                }
                read++;
                LOGGER.debug("table={} column={} comparison={} const={} partition={} min={} max={} decision={}",
                        LogSafe.value(tableName), LogSafe.value(columnName), LogSafe.value(comparison),
                        LogSafe.value(constant), pe.index(),
                        LogSafe.value(sum == null ? null : sum.min()),
                        LogSafe.value(sum == null ? null : sum.max()),
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

            lastScanStats = new ScanStats(total, read, pruned);
            LOGGER.debug("table={} column={} comparison={} const={} partitionsRead={} partitionsPruned={} rowsOut={} durationMs={}",
                    LogSafe.value(tableName), LogSafe.value(columnName), LogSafe.value(comparison),
                    LogSafe.value(constant), read, pruned, out.size(), elapsedMs(start));
            return out;
        } catch (RuntimeException e) {
            logFailure("select",
                    "table=" + LogSafe.value(tableName) + " column=" + LogSafe.value(columnName)
                            + " comparison=" + LogSafe.value(comparison) + " const=" + LogSafe.value(constant),
                    start, e);
            throw e;
        }
    }

    /** Stats from the most recent {@link #select} call. */
    public ScanStats lastScanStats() {
        if (lastScanStats == null) throw new IllegalStateException("no select has been executed yet");
        return lastScanStats;
    }

    private static void validateConstant(ColumnSpec col, Object constant) {
        if (constant == null) throw new IllegalArgumentException("constant must not be null");
        boolean ok = switch (col.type()) {
            case STRING -> constant instanceof String;
            case LONG -> constant instanceof Long;
            case DOUBLE -> constant instanceof Double;
        };
        if (!ok)
            throw new IllegalArgumentException(
                    "constant type mismatch for column " + col.name()
                            + ": expected " + col.type() + " got " + constant.getClass().getSimpleName());
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
