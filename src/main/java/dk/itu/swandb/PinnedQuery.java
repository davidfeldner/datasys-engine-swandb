package dk.itu.swandb;

import java.util.ArrayList;
import java.util.List;

import dk.itu.swandb.plan.Operator;
import dk.itu.swandb.plan.Plan;

/**
 * A planned query over one pinned table version, or over one pinned version
 * per table of a multi-table join, the handle {@link StorageEngine#openQuery}
 * returns.
 *
 * <p>The plan is built from the pinned partition lists, so it carries its
 * snapshot's partitions with their offsets and counts instead of indices into
 * whatever the catalog holds later. Draining it after a concurrent commit
 * therefore returns the pinned versions' rows (Exercise 8).
 *
 * <p>Close the query to release the pins. Until then the versions' bytes stay
 * in the data file, because a pin keeps every live version readable.
 */
public final class PinnedQuery implements AutoCloseable {

    private final List<VersionedTable.PinnedVersion> pinned;
    private final Plan plan;

    PinnedQuery(VersionedTable.PinnedVersion pinned, Plan plan) {
        this(List.of(pinned), plan);
    }

    PinnedQuery(List<VersionedTable.PinnedVersion> pinned, Plan plan) {
        if (pinned == null || pinned.isEmpty())
            throw new IllegalArgumentException("a query must pin at least one version");
        this.pinned = List.copyOf(pinned);
        this.plan = plan;
    }

    /** The operator tree to open and drain. */
    public Operator root() {
        return plan.root();
    }

    /** The pruning figures the planner observed while building the plan. */
    public ScanStats stats() {
        return plan.stats();
    }

    /**
     * The newest version this query pinned. For a single-table query that is
     * the version it reads; for a join it is the largest of the pinned
     * versions, which is the one the summary line reports.
     */
    public int version() {
        int newest = 0;
        for (VersionedTable.PinnedVersion version : pinned) newest = Math.max(newest, version.number());
        return newest;
    }

    /** Drains the plan in one go, always closing the operator tree. */
    public List<Object[]> drain() {
        List<Object[]> out = new ArrayList<>();
        Operator root = plan.root();
        root.open();
        try {
            Object[] row;
            while ((row = root.next()) != null) out.add(row);
        } finally {
            root.close();
        }
        return out;
    }

    @Override
    public void close() {
        for (VersionedTable.PinnedVersion version : pinned) version.close();
    }
}
