package dk.itu.swandb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.swandb.enums.Comparison;
import dk.itu.swandb.plan.Operator;
import dk.itu.swandb.plan.Plan;
import dk.itu.swandb.plan.Planner;
import dk.itu.swandb.plan.Selection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
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
        if (maxRowsPerPartition <= 0)
            throw new IllegalArgumentException("maxRowsPerPartition must be > 0");
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

    /**
     * The table's schema, in column order. Throws
     * {@link IllegalArgumentException} if the table is unknown. Read-only:
     * it exists so the SQL binder can validate names and types against the
     * catalog without going through a scan.
     */
    public List<ColumnSpec> schema(String tableName) {
        long start = System.nanoTime();
        try {
            Catalog.TableEntry table = catalog.getTable(tableName);
            List<ColumnSpec> columns = List.copyOf(table.columns);
            long durationMs = (System.nanoTime() - start) / 1_000_000L;
            LOGGER.debug("table={} columns={} durationMs={}",
                    LogFormat.sanitize(tableName), columns.size(), durationMs);
            return columns;
        } catch (RuntimeException e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000L;
            LOGGER.debug("table={} error={} durationMs={}",
                    LogFormat.sanitize(tableName), e.getClass().getSimpleName(), durationMs);
            throw e;
        }
    }

    /** Persist a new table schema. */
    public void createTable(String tableName, List<ColumnSpec> columns) {
        long start = System.nanoTime();
        if (tableName == null || tableName.isEmpty())
            throw new IllegalArgumentException("table name must not be empty");
        if (columns == null || columns.isEmpty())
            throw new IllegalArgumentException("column list must not be empty");
        if (catalog.hasTable(tableName))
            throw new IllegalArgumentException("table already exists: " + tableName);
        Set<String> seen = new HashSet<>();
        for (ColumnSpec c : columns) {
            if (!seen.add(c.name()))
                throw new IllegalArgumentException("duplicate column name: " + c.name());
        }

        String dataFile = tableName + SwanFile.EXTENSION;
        catalog.addTable(new Catalog.TableEntry(tableName, columns, dataFile, maxRowsPerPartition));
        catalog.save();

        long durationMs = (System.nanoTime() - start) / 1_000_000L;
        LOGGER.debug("table={} columns={} durationMs={}",
                LogFormat.sanitize(tableName), columns.size(), durationMs);
    }

    /** Load a CSV file into the binary store, splitting into partitions. */
    public void copyFile(String tableName, String csvFilePath) {
        long start = System.nanoTime();
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
                        LogFormat.sanitize(tableName), partitionIndex, LogFormat.sanitize(spec.name()),
                        LogFormat.sanitize(mm.min()), LogFormat.sanitize(mm.max()));
            }
            partEntries.add(new Catalog.PartitionEntry(partitionIndex, end - offset, summaries));
        }

        Path dataPath = dataDir.resolve(table.dataFile);
        try {
            List<Long> partitionOffsets = SwanFile.write(dataPath, table.columns, partitions);
            for (int i = 0; i < partEntries.size(); i++) {
                partEntries.get(i).offset = partitionOffsets.get(i);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to write " + dataPath, e);
        }

        table.partitions = partEntries;
        catalog.save();

        long durationMs = (System.nanoTime() - start) / 1_000_000L;
        LOGGER.debug("table={} file={} rows={} partitions={} durationMs={}",
                LogFormat.sanitize(tableName), LogFormat.sanitize(csvFilePath), totalRows,
                partitions.size(), durationMs);
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

    /**
     * Filtered scan over the binary store. The week 2 signature is
     * unchanged; internally it plans a {@code Filter(Scan)} pipeline and
     * drains it. Planning is where pruning happens, using catalog min/max
     * summaries only.
     */
    public List<Object[]> select(String tableName, String columnName,
            Comparison comparison, Object constant) {
        long start = System.nanoTime();
        String safeConst = LogFormat.sanitize(constant);
        try {
            Selection selection = new Selection(columnName, comparison, constant);
            Plan plan = new Planner(catalog, dataDir).plan(tableName, Optional.of(selection));
            List<Object[]> out = drain(plan.root());
            lastScanStats = plan.stats();

            long durationMs = (System.nanoTime() - start) / 1_000_000L;
            LOGGER.debug(
                    "table={} column={} comparison={} const={} partitionsRead={} partitionsPruned={} rowsOut={} durationMs={}",
                    LogFormat.sanitize(tableName), LogFormat.sanitize(columnName), comparison, safeConst,
                    plan.stats().partitionsRead(), plan.stats().partitionsPruned(),
                    out.size(), durationMs);
            return out;
        } catch (RuntimeException e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000L;
            LOGGER.debug("table={} column={} comparison={} const={} error={} durationMs={}",
                    LogFormat.sanitize(tableName), LogFormat.sanitize(columnName), comparison, safeConst,
                    e.getClass().getSimpleName(), durationMs);
            throw e;
        }
    }

    /**
     * Unfiltered scan over the binary store: every partition survives and
     * the plan is a bare {@link dk.itu.swandb.plan.ScanOperator}.
     */
    public List<Object[]> selectAll(String tableName) {
        long start = System.nanoTime();
        try {
            Plan plan = new Planner(catalog, dataDir).plan(tableName, Optional.empty());
            List<Object[]> out = drain(plan.root());
            lastScanStats = plan.stats();

            long durationMs = (System.nanoTime() - start) / 1_000_000L;
            LOGGER.debug(
                    "table={} partitionsRead={} partitionsPruned={} rowsOut={} durationMs={}",
                    LogFormat.sanitize(tableName), plan.stats().partitionsRead(),
                    plan.stats().partitionsPruned(), out.size(), durationMs);
            return out;
        } catch (RuntimeException e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000L;
            LOGGER.debug("table={} error={} durationMs={}",
                    LogFormat.sanitize(tableName), e.getClass().getSimpleName(), durationMs);
            throw e;
        }
    }

    /** Drains an operator tree, always closing what it opened. */
    private static List<Object[]> drain(Operator root) {
        List<Object[]> out = new ArrayList<>();
        root.open();
        try {
            Object[] row;
            while ((row = root.next()) != null) {
                out.add(row);
            }
        } finally {
            root.close();
        }
        return out;
    }

    /** Stats from the most recent {@link #select} or {@link #selectAll} call. */
    public ScanStats lastScanStats() {
        if (lastScanStats == null)
            throw new IllegalStateException("no select has been executed yet");
        return lastScanStats;
    }
}
