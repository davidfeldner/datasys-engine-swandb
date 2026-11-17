package dk.itu.swandb;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One table's versioned metadata, after {@code docs/transactions-design.md}.
 *
 * <p>{@code CREATE TABLE} makes version 0, the empty table. Every committed
 * {@code COPY} or {@code DELETE} builds a new partition list and commits it
 * as base + 1. A version <em>is</em> a partition list nobody may modify; the
 * lists and their {@link Catalog.PartitionEntry}s are immutable, so any
 * number of readers may share one while another statement commits.
 *
 * <p><b>Where versions live.</b> A table has one data file, and partitions
 * are only ever appended at its end, so bytes already written never move and
 * an offset a version recorded keeps pointing at the same partition. The
 * newest version is also the one in {@code catalog.json}; older versions
 * live in memory only and are gone after a restart.
 *
 * <p><b>Commit</b> stores {@code base + 1} in the table's
 * {@link ConcurrentHashMap} version sink, plus one reference assignment that
 * publishes it as newest. Since Exercise 8 the first committer wins: a writer
 * whose base is no longer newest validates against the moved version and gets
 * a {@link ConflictException}, so only one of two racing writers can commit
 * from the same base.
 *
 * <p><b>Snapshot.</b> A statement calls {@link #begin()} and gets the newest
 * committed version, by reference, and holds it until
 * {@link PinnedVersion#close()}. No metadata is re-read after that: the plan,
 * and a DELETE's rewrite set, are built from the pinned list.
 *
 * <p><b>Retention</b> is reference counting with a retention window. A version
 * stays while a statement pins it, or pins a newer one, or as a successor
 * whose file is still needed, and until {@link #RETAINED_VERSIONS} versions
 * have been committed above it. Live versions are therefore always a suffix
 * of {@code 0..newest}: the lowest survivor is {@link #firstLiveVersion()},
 * and the next commit truncates the data file back to the end of the last
 * partition that version points at before it appends. A statement that asks
 * for a version which is gone gets {@link StaleVersionException}.
 */
public final class VersionedTable {

    private static final Logger LOGGER = LoggerFactory.getLogger(VersionedTable.class);

    /** How many versions stay readable at least, counting from the newest. */
    public static final int RETAINED_VERSIONS = 4;

    /** A committed partition list, immutable from the moment it is stored. */
    public static final class Version {
        private final int number;
        private final List<Catalog.PartitionEntry> partitions;
        /** Statements that pin this version or any newer live one. Guarded by the table's monitor. */
        private int pins;

        private Version(int number, List<Catalog.PartitionEntry> partitions) {
            this.number = number;
            this.partitions = List.copyOf(partitions);
        }

        public int number() { return number; }
        public List<Catalog.PartitionEntry> partitions() { return partitions; }
    }

    /** A statement's hold on the version it reads. Close it when the statement is done. */
    public static final class PinnedVersion implements AutoCloseable {
        private final VersionedTable owner;
        private final Version version;
        private boolean released;

        private PinnedVersion(VersionedTable owner, Version version) {
            this.owner = owner;
            this.version = version;
        }

        public int number() { return version.number; }
        public List<Catalog.PartitionEntry> partitions() { return version.partitions; }

        /** Idempotent: a second close must not release somebody else's pin. */
        @Override
        public synchronized void close() {
            if (released) return;
            released = true;
            owner.release();
        }
    }

    private final String name;
    private final Map<Integer, Version> versions = new ConcurrentHashMap<>();
    /** Publishes the swap: the newest committed version. */
    private volatile Version newest;

    private VersionedTable(String name, int version, List<Catalog.PartitionEntry> partitions) {
        this.name = name;
        Version v = new Version(version, partitions);
        versions.put(version, v);
        this.newest = v;
    }

    /** The empty table of {@code CREATE TABLE}: version 0, no partitions. */
    public static VersionedTable empty(String tableName) {
        return new VersionedTable(tableName, 0, List.of());
    }

    /** A table reloaded from a persisted catalog, at the version it was written with. */
    public static VersionedTable recovered(Catalog.TableEntry entry) {
        return new VersionedTable(entry.name, entry.version, entry.partitions);
    }

    public String name() { return name; }

    /** The newest committed version number: the one a statement reads. */
    public int newestNumber() {
        return newest.number;
    }

    /** The live version numbers, oldest first. Diagnostic and test use. */
    public synchronized List<Integer> liveVersions() {
        List<Integer> out = new ArrayList<>(versions.keySet());
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /** The lowest live version: the truncation point of the data file. */
    public synchronized int firstLiveVersion() {
        int lowest = newest.number;
        for (int n : versions.keySet()) {
            lowest = Math.min(lowest, n);
        }
        return lowest;
    }

    /** The newest live partition list: what {@code catalog.json} records. */
    public Version newest() {
        return newest;
    }

    /**
     * Pins the newest committed version, and with it every older live version,
     * because the pin is what keeps the data file a statement reads from
     * append-only valid. The caller must close the pin.
     */
    public synchronized PinnedVersion begin() {
        Version v = newest;
        for (Version live : versions.values()) {
            live.pins++;
        }
        return new PinnedVersion(this, v);
    }

    private synchronized void release() {
        for (Version live : versions.values()) {
            live.pins--;
        }
        collectLocked();
    }

    /**
     * Validates a writer's base version, the check Exercise 8 calls commit
     * validation.
     *
     * @throws ConflictException if another writer committed first; the log
     *         then carries the {@code conflict table=... base=... current=...}
     *         ERROR line.
     * @throws StaleVersionException if the base version is no longer live,
     *         which means retention dropped it while this writer ran.
     */
    public synchronized void validate(int expectedVersion) {
        Version base = versions.get(expectedVersion);
        if (base == null)
            throw new StaleVersionException(name, expectedVersion);
        if (base != newest) {
            LOGGER.error("conflict table={} base={} current={}",
                    LogFormat.sanitize(name), expectedVersion, newest.number);
            throw new ConflictException(name, expectedVersion, newest.number);
        }
    }

    /**
     * Commits {@code partitions} as version {@code expectedVersion + 1}.
     * Validates first, so a statement that lost the race throws instead of
     * swapping its partition list in on top of the winner's.
     *
     * @return the committed version number.
     * @throws ConflictException if another writer committed first.
     * @throws StaleVersionException if the expected version is no longer live.
     */
    public synchronized int commit(int expectedVersion, List<Catalog.PartitionEntry> partitions) {
        validate(expectedVersion);
        Version next = new Version(expectedVersion + 1, partitions);
        versions.put(next.number, next);
        newest = next;
        LOGGER.debug("table={} version={} partitions={} committed",
                LogFormat.sanitize(name), next.number, next.partitions.size());
        collectLocked();
        return next.number;
    }

    /**
     * Drops every version that is superseded, unpinned and outside the
     * retention window. Only the bottom of the range goes, so the survivors
     * stay contiguous and {@link #firstLiveVersion()} is where the data file
     * is truncated.
     */
    public synchronized void collect() {
        collectLocked();
    }

    private void collectLocked() {
        while (true) {
            int lowest = firstLiveVersion();
            if (lowest == newest.number) return;
            if (newest.number - lowest < RETAINED_VERSIONS) return;
            Version v = versions.get(lowest);
            if (v.pins != 0) return;
            versions.remove(lowest);
            LOGGER.debug("table={} version={} expired={}", LogFormat.sanitize(name), lowest, true);
        }
    }

    /**
     * The byte the data file is truncated back to before the next append: the
     * end of the latest partition any live version still points at.
     * Everything past it is garbage — partitions of expired versions, or
     * partitions of a commit that never happened — and costs space.
     *
     * @param metadataEnd the byte at which the data file's partitions start.
     */
    public synchronized long liveEnd(long metadataEnd) {
        long end = metadataEnd;
        for (Version v : versions.values()) {
            for (Catalog.PartitionEntry p : v.partitions) {
                if (p.offset() >= 0) end = Math.max(end, p.endOffset());
            }
        }
        return end;
    }
}
