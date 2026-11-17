package dk.itu.swandb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.swandb.enums.Comparison;
import dk.itu.swandb.plan.Plan;
import dk.itu.swandb.plan.Planner;
import dk.itu.swandb.plan.Selection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent storage engine for headerless CSV input. Reads/writes a
 * {@code catalog.json} plus one {@code .swan} file per table. All state
 * lives under the directory passed to the constructor, so each test
 * gets a fresh tree and a restart is just another instance.
 *
 * <p>Since Exercise 7 every table is versioned ({@link VersionedTable}).
 * No partition is ever modified in place: {@code COPY} and {@code DELETE}
 * write their new partitions at the end of the table's data file and commit
 * the new partition list and version in one atomic swap. Since Exercise 8 the
 * swap validates the writer's base first, so two writers racing from the same
 * version cannot both commit; the loser throws {@link ConflictException}.
 * The catalog file is written when the engine closes ({@link #close()}), and
 * by nothing else after {@code CREATE TABLE}.
 */
public final class StorageEngine implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(StorageEngine.class);

    /** Default maximum rows per partition. */
    public static final int DEFAULT_MAX_ROWS_PER_PARTITION = 10_000;

    private final Path dataDir;
    private final int maxRowsPerPartition;
    private final Catalog catalog;
    /** Schemas and version chains, one entry per table. */
    private final Map<String, VersionedTable> tables = new ConcurrentHashMap<>();
    /** Figures of the last SELECT, for the executor's summary line. */
    private volatile int lastReadVersion;
    private volatile int lastPartitionsRead;
    private volatile int lastPartitionsPruned;
    /** Serialises a table's commits: write the new partitions, then swap. */
    private final Map<String, Object> commitLocks = new ConcurrentHashMap<>();

    /**
     * Runs before a commit's validation. Production never touches it; a test
     * parks a writer here. Package-private and a no-op by default, so forcing
     * the two-writer interleaving costs production nothing and needs no
     * sleeps (Exercise 8).
     */
    Runnable beforeCommit = () -> { };

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
        for (Catalog.TableEntry entry : catalog.tables()) {
            tables.put(entry.name, VersionedTable.recovered(entry));
        }
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
            List<ColumnSpec> columns = List.copyOf(entry(tableName).columns);
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

    /**
     * {@code CREATE TABLE} is version 0, the empty table. It writes the
     * catalog at once: a table that exists but was never copied into must
     * still be there for the next engine, as it was in Part 1.
     */
    public Result createTable(String tableName, List<ColumnSpec> columns) {
        long start = System.nanoTime();
        if (tableName == null || tableName.isEmpty())
            throw new IllegalArgumentException("table name must not be empty");
        if (columns == null || columns.isEmpty())
            throw new IllegalArgumentException("column list must not be empty");
        if (tables.containsKey(tableName) || catalog.hasTable(tableName))
            throw new IllegalArgumentException("table already exists: " + tableName);
        Set<String> seen = new HashSet<>();
        for (ColumnSpec c : columns) {
            if (!seen.add(c.name()))
                throw new IllegalArgumentException("duplicate column name: " + c.name());
        }

        Catalog.TableEntry entry = new Catalog.TableEntry(
                tableName, columns, tableName + SwanFile.EXTENSION, maxRowsPerPartition);
        entry.version = 0;
        catalog.addTable(entry);
        tables.put(tableName, VersionedTable.empty(tableName));
        catalog.save();

        long durationMs = (System.nanoTime() - start) / 1_000_000L;
        LOGGER.debug("table={} columns={} version={} durationMs={}",
                LogFormat.sanitize(tableName), columns.size(), 0, durationMs);
        return Result.created(0);
    }

    /**
     * Load a CSV file into the binary store as the table's next version: the
     * CSV is cut into partitions of {@code maxRowsPerPartition} rows, those
     * are appended after the partitions every live version still needs, and
     * the new partition list is committed once it is written. A second COPY
     * into a table with data simply appends more partitions.
     *
     * <p>The statement pins the newest version when it starts and commits on
     * top of that base. If another writer committed in between, validation
     * fails with {@link ConflictException} and this COPY writes nothing.
     */
    public Result copyFile(String tableName, String csvFilePath) {
        long start = System.nanoTime();
        List<ColumnSpec> columns = schema(tableName);
        Catalog.TableEntry entry = entry(tableName);
        Path csvPath = Path.of(csvFilePath);
        List<Object[]> rows;
        try {
            rows = CsvParser.read(csvPath, columns);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + csvPath, e);
        }

        List<SwanFile.Partition> partitions = new ArrayList<>();
        List<Catalog.PartitionEntry> newEntries = new ArrayList<>();
        for (int offset = 0; offset < rows.size(); offset += maxRowsPerPartition) {
            int end = Math.min(offset + maxRowsPerPartition, rows.size());
            List<Object[]> slice = rows.subList(offset, end);
            partitions.add(buildPartition(columns, slice));
            newEntries.add(summarize(columns, slice, newEntries.size(), -1));
        }

        VersionedTable table = versioned(tableName);
        int version;
        try (VersionedTable.PinnedVersion pinned = table.begin()) {
            int base = pinned.number();
            List<Catalog.PartitionEntry> next = new ArrayList<>(pinned.partitions());
            next.addAll(newEntries);
            beforeCommit.run();
            synchronized (lock(tableName)) {
                // Validate before writing: a loser writes nothing, so it leaves
                // no orphan behind, and the append sees the newest version.
                table.validate(base);
                listPartitions(newEntries, partitions, table, base + 1, entry, columns);
                reindex(next);
                version = commit(table, base, next);
            }
        }

        long durationMs = (System.nanoTime() - start) / 1_000_000L;
        LOGGER.debug("table={} file={} rows={} partitions={} version={} durationMs={}",
                LogFormat.sanitize(tableName), LogFormat.sanitize(csvFilePath), rows.size(),
                partitions.size(), version, durationMs);
        return Result.copied(version, rows.size(), partitions.size());
    }

    /**
     * Delete every row matching {@code where} — or every row, for an empty
     * {@code where} — as the table's next version.
     *
     * <p>The rewrite set is the complement of the planner's pruning decision
     * ({@link Pruning#mustRewrite}): a partition that can match is read,
     * copied without its matching rows and appended at the end of the data
     * file; a partition that cannot match is carried over untouched. If
     * nothing matches, nothing is written and no version is created: the
     * table stays at the version it was at.
     *
     * <p>The statement pins the newest version when it starts and commits on
     * top of that base. If another writer committed in between, validation
     * fails with {@link ConflictException} and this DELETE writes nothing.
     */
    public Result delete(String tableName, Optional<Selection> where) {
        long start = System.nanoTime();
        List<ColumnSpec> columns = schema(tableName);
        Catalog.TableEntry entry = entry(tableName);
        int columnIndex = where.isEmpty() ? -1 : columnIndex(columns, where.get().columnName());
        ColumnSpec filterColumn = columnIndex < 0 ? null : columns.get(columnIndex);
        if (filterColumn != null)
            requireConstant(filterColumn, where.get().constant());

        int version;
        int deleted = 0;
        int carried = 0;
        int rewritten = 0;
        VersionedTable table = versioned(tableName);
        try (VersionedTable.PinnedVersion pinned = table.begin()) {
            int base = pinned.number();
            // Parallel to the base list: the partition to carry at each
            // position, or null when that position is rewritten or dropped.
            List<Catalog.PartitionEntry> next = new ArrayList<>(pinned.partitions().size());
            // For each rewrite that produces a new partition, the position in
            // next it replaces.
            List<Integer> rewriteSlots = new ArrayList<>();
            List<SwanFile.Partition> rewrittenBodies = new ArrayList<>();
            List<Catalog.PartitionEntry> rewriteEntries = new ArrayList<>();
            for (Catalog.PartitionEntry partition : pinned.partitions()) {
                if (where.isPresent() && cannotMatch(partition, where.get(), where.get().columnName(), filterColumn)) {
                    next.add(partition);
                    carried++;
                    continue;
                }
                List<Object[]> kept = new ArrayList<>();
                for (Object[] row : readRows(tableName, partition, columns)) {
                    // Without a WHERE every row matches: that is the
                    // "DELETE FROM table;" that empties the table.
                    boolean matches = where.isEmpty()
                            || Pruning.matches(where.get().comparison(),
                                    where.get().constant(), row[columnIndex], filterColumn.type());
                    if (matches) {
                        deleted++;
                    } else {
                        kept.add(row);
                    }
                }
                if (kept.size() == partition.rowCount()) {
                    // May match, but holds nothing to remove: carry it over.
                    next.add(partition);
                    carried++;
                } else if (kept.isEmpty()) {
                    // Every row matched: a rewrite that produces no
                    // partition, so nothing is written for it.
                    rewritten++;
                    next.add(null);
                } else {
                    rewriteSlots.add(next.size());
                    rewritten++;
                    rewrittenBodies.add(buildPartition(columns, kept));
                    rewriteEntries.add(summarize(columns, kept, rewriteEntries.size(), -1));
                    next.add(null);
                }
            }

            if (deleted == 0) {
                version = base;
            } else {
                beforeCommit.run();
                synchronized (lock(tableName)) {
                    // Validate before writing: a loser writes nothing, so it
                    // leaves no orphan behind.
                    table.validate(base);
                    listPartitions(rewriteEntries, rewrittenBodies, table, base + 1, entry, columns);
                    for (int r = 0; r < rewriteSlots.size(); r++) {
                        next.set(rewriteSlots.get(r), rewriteEntries.get(r));
                    }
                    // Dropped partition positions stay null and leave the table.
                    List<Catalog.PartitionEntry> committed = new ArrayList<>();
                    for (Catalog.PartitionEntry p : next) {
                        if (p != null) committed.add(p);
                    }
                    reindex(committed);
                    version = commit(table, base, committed);
                }
            }
        }

        long durationMs = (System.nanoTime() - start) / 1_000_000L;
        LOGGER.debug("table={} version={} rowsDeleted={} partitionsRewritten={} partitionsCarried={} durationMs={}",
                LogFormat.sanitize(tableName), version, deleted, rewritten, carried, durationMs);
        return Result.deleted(version, deleted, rewritten, carried);
    }

    /**
     * The rows of the table's newest committed version, pruned and filtered
     * when {@code where} is present and unfiltered when it is not. The
     * version read is published through {@link #lastReadVersion()}.
     */
    public List<Object[]> select(String tableName, Optional<Selection> where) {
        long start = System.nanoTime();
        try (PinnedQuery query = openQuery(tableName, where)) {
            List<Object[]> out = query.drain();
            lastReadVersion = query.version();
            lastPartitionsRead = query.stats().partitionsRead();
            lastPartitionsPruned = query.stats().partitionsPruned();
            long durationMs = (System.nanoTime() - start) / 1_000_000L;
            LOGGER.debug(
                    "table={} rowsOut={} partitionsRead={} partitionsPruned={} version={} durationMs={}",
                    LogFormat.sanitize(tableName), out.size(), query.stats().partitionsRead(),
                    query.stats().partitionsPruned(), query.version(), durationMs);
            return out;
        }
    }

    /**
     * Opens a scan on the newest committed version and pins it until the
     * returned query is closed. The plan is built from the pinned partition
     * list, so the scan carries its snapshot's partitions, offsets and counts:
     * a commit that lands between opening and draining changes neither the
     * plan nor the rows it reads.
     */
    public PinnedQuery openQuery(String tableName, Optional<Selection> where) {
        Catalog.TableEntry entry = entry(tableName);
        VersionedTable.PinnedVersion pinned = versioned(tableName).begin();
        try {
            Plan plan = new Planner(entry.columns, pinned.partitions(), dataDir.resolve(entry.dataFile))
                    .plan(tableName, where);
            return new PinnedQuery(pinned, plan);
        } catch (RuntimeException e) {
            pinned.close();
            throw e;
        }
    }

    /**
     * The newest committed version of {@code tableName}: the one a statement
     * starting now would read, and the one {@code catalog.json} records.
     */
    public int version(String tableName) {
        return versioned(tableName).newestNumber();
    }

    /**
     * The versions of {@code tableName} that are still live, oldest first.
     * The newest is the one statements read; the others are in memory only
     * and are visible here until the retention window drops them.
     */
    public List<Integer> liveVersions(String tableName) {
        return versioned(tableName).liveVersions();
    }

    /** The version the last {@code SELECT} pinned and read. */
    public int lastReadVersion() {
        return lastReadVersion;
    }

    /** How many partitions the last {@code SELECT} read, and how many it pruned. */
    public int lastPartitionsRead() {
        return lastPartitionsRead;
    }

    public int lastPartitionsPruned() {
        return lastPartitionsPruned;
    }

    /** Filtered scan over the binary store: the week 2 signature, now versioned. */
    public List<Object[]> select(String tableName, String columnName, Comparison comparison, Object constant) {
        return select(tableName, Optional.of(new Selection(columnName, comparison, constant)));
    }

    /** Unfiltered scan over the binary store: every partition is read. */
    public List<Object[]> selectAll(String tableName) {
        return select(tableName, Optional.empty());
    }

    /**
     * Persist every table's newest version and drop the in-memory ones, for a
     * caller that is done and may start a fresh engine next. Closing twice is
     * harmless.
     */
    @Override
    public synchronized void close() {
        for (VersionedTable table : tables.values()) {
            table.collect();
            persist(table);
        }
        catalog.save();
    }

    // --- versioned commit ----------------------------------------------

    /**
     * Commits {@code next} on top of the validated {@code base} and makes it
     * the version the catalog will persist. The caller holds the table's
     * commit lock, so no other writer can slip in between the validation and
     * this swap.
     */
    private int commit(VersionedTable table, int base, List<Catalog.PartitionEntry> next) {
        int version = table.commit(base, next);
        persist(table);
        return version;
    }

    /**
     * Appends the written partitions at the end of the data file and gives
     * each its final offset and version. Truncating back to {@link
     * VersionedTable#liveEnd} first is what reclaims the bytes of versions
     * that expired and of commits that never happened.
     */
    private void listPartitions(List<Catalog.PartitionEntry> entries, List<SwanFile.Partition> bodies,
                                VersionedTable table, int version, Catalog.TableEntry entry,
                                List<ColumnSpec> columns) {
        if (bodies.isEmpty()) return;
        for (Catalog.PartitionEntry e : entries) e.version = version;
        Path dataPath = dataDir.resolve(entry.dataFile);
        long metadataEnd = SwanFile.metadataBytes(columns.size());
        long keepEnd = table.liveEnd(metadataEnd);
        int kept = keptPartitions(table, keepEnd);
        List<Long> offsets;
        List<Long> ends;
        try {
            if (Files.exists(dataPath)) {
                offsets = SwanFile.append(dataPath, columns, bodies, keepEnd, kept);
            } else {
                // The table's first partitions: there is no file to append to,
                // and the header has to be written with them.
                offsets = SwanFile.write(dataPath, columns, bodies);
            }
            long size = Files.size(dataPath);
            ends = endsOf(offsets, size);
        } catch (IOException e) {
            throw new IllegalStateException("failed to write " + dataPath, e);
        }
        for (int i = 0; i < entries.size(); i++) {
            entries.get(i).offset = offsets.get(i);
            entries.get(i).endOffset = ends.get(i);
        }
    }

    /**
     * How many partitions end at or before {@code keepEnd}. Every live
     * version points into the same file, so the newest version's list names
     * all the partitions that are still reachable; the ones kept are those
     * that end before the truncation point.
     */
    private static int keptPartitions(VersionedTable table, long keepEnd) {
        int kept = 0;
        for (Catalog.PartitionEntry p : table.newest().partitions()) {
            if (p.endOffset() >= 0 && p.endOffset() <= keepEnd) kept++;
        }
        return kept;
    }

    private static List<Long> endsOf(List<Long> offsets, long fileSize) {
        List<Long> ends = new ArrayList<>(offsets.size());
        for (int i = 0; i < offsets.size(); i++) {
            ends.add(i + 1 < offsets.size() ? offsets.get(i + 1) : fileSize);
        }
        return ends;
    }

    // --- helpers -------------------------------------------------------

    private Catalog.TableEntry entry(String tableName) {
        return catalog.getTable(tableName);
    }

    private VersionedTable versioned(String tableName) {
        VersionedTable table = tables.get(tableName);
        if (table == null) {
            VersionedTable created = VersionedTable.recovered(entry(tableName));
            VersionedTable raced = tables.putIfAbsent(tableName, created);
            table = raced == null ? created : raced;
        }
        return table;
    }

    private Object lock(String tableName) {
        return commitLocks.computeIfAbsent(tableName, k -> new Object());
    }

    /** Records the newest version in the catalog document; saved on close. */
    private void persist(VersionedTable table) {
        Catalog.TableEntry entry = entry(table.name());
        entry.version = table.newestNumber();
        entry.partitions = new ArrayList<>(table.newest().partitions());
    }

    /**
     * Gives every partition of a committed list its position as index. The
     * index identifies a partition within a version; the file offset is what
     * a scan seeks to.
     */
    private static void reindex(List<Catalog.PartitionEntry> partitions) {
        List<Catalog.PartitionEntry> copies = new ArrayList<>(partitions.size());
        for (int i = 0; i < partitions.size(); i++) {
            Catalog.PartitionEntry copy = partitions.get(i).copy();
            copy.index = i;
            copies.add(copy);
        }
        partitions.clear();
        partitions.addAll(copies);
    }

    private static int columnIndex(List<ColumnSpec> columns, String columnName) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(columnName))
                return i;
        }
        throw new IllegalArgumentException("unknown column: " + columnName);
    }

    private static void requireConstant(ColumnSpec column, Object constant) {
        if (constant == null)
            throw new IllegalArgumentException("constant must not be null");
        if (!column.type().accepts(constant))
            throw new IllegalArgumentException(
                    "constant type mismatch for column " + column.name()
                            + ": expected " + column.type() + " got " + constant.getClass().getSimpleName());
    }

    /**
     * Whether a partition proves it holds no matching row. The complement of
     * the planner's pruning decision: this is the one decision both use.
     */
    private static boolean cannotMatch(Catalog.PartitionEntry partition, Selection selection,
                                       String columnName, ColumnSpec column) {
        Catalog.ColumnSummary summary = summary(partition, columnName);
        if (summary == null || summary.min() == null || summary.max() == null)
            throw new IllegalStateException("missing min/max summary for column " + columnName
                    + " in partition " + partition.index + "; refusing to decide the rewrite set without it");
        return !Pruning.mustRewrite(selection.comparison(), selection.constant(),
                summary.min(), summary.max(), column.type());
    }

    private static Catalog.ColumnSummary summary(Catalog.PartitionEntry partition, String columnName) {
        for (Catalog.ColumnSummary summary : partition.columns) {
            if (summary.name().equals(columnName))
                return summary;
        }
        return null;
    }

    private List<Object[]> readRows(String tableName, Catalog.PartitionEntry partition,
                                    List<ColumnSpec> columns) {
        try {
            Path path = dataDir.resolve(entry(tableName).dataFile);
            // Every partition written since Exercise 7 carries its offset; only
            // a catalog from before offset tracking falls back to the
            // sequential read, which the io-pruning fix kept (docs/io-pruning.md).
            SwanFile.Partition body = partition.offset() < 0
                    ? SwanFile.readAll(path, columns).get(partition.index)
                    : SwanFile.readPartition(path, columns, partition.offset());
            List<Object[]> rows = new ArrayList<>(body.rowCount);
            for (int r = 0; r < body.rowCount; r++) {
                Object[] row = new Object[columns.size()];
                for (int c = 0; c < columns.size(); c++) {
                    row[c] = body.columnValues.get(c).get(r);
                }
                rows.add(row);
            }
            return rows;
        } catch (IOException e) {
            throw new IllegalStateException("failed to read partition " + partition.index, e);
        }
    }

    private static SwanFile.Partition buildPartition(List<ColumnSpec> schema, List<Object[]> rows) {
        List<List<Object>> cols = new ArrayList<>(schema.size());
        for (int c = 0; c < schema.size(); c++) {
            List<Object> values = new ArrayList<>(rows.size());
            for (Object[] row : rows) values.add(row[c]);
            cols.add(values);
        }
        return new SwanFile.Partition(rows.size(), cols);
    }

    private static Catalog.PartitionEntry summarize(List<ColumnSpec> schema, List<Object[]> rows,
                                                    int index, long offset) {
        List<Catalog.ColumnSummary> summaries = new ArrayList<>(schema.size());
        for (int c = 0; c < schema.size(); c++) {
            List<Object> values = new ArrayList<>(rows.size());
            for (Object[] row : rows) values.add(row[c]);
            MinMax.Result mm = MinMax.compute(schema.get(c).type(), values);
            summaries.add(new Catalog.ColumnSummary(schema.get(c).name(), schema.get(c).type(), mm.min(), mm.max()));
        }
        Catalog.PartitionEntry entry = new Catalog.PartitionEntry(index, rows.size(), summaries);
        entry.offset = offset;
        return entry;
    }
}
