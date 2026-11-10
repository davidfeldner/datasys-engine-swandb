package dk.itu.swandb;

import java.util.ArrayList;
import java.util.List;

import dk.itu.swandb.plan.Operator;
import dk.itu.swandb.plan.Plan;

/**
 * A planned query over one pinned table version, the handle
 * {@link StorageEngine#openQuery} returns.
 *
 * <p>The plan is built from the pinned partition list, so it carries its
 * snapshot's partitions with their offsets and counts instead of indices into
 * whatever the catalog holds later. Draining it after a concurrent commit
 * therefore returns the pinned version's rows (Exercise 8).
 *
 * <p>Close the query to release the pin. Until then the version's bytes stay
 * in the data file, because a pin keeps every live version readable.
 */
public final class PinnedQuery implements AutoCloseable {

    private final VersionedTable.PinnedVersion pinned;
    private final Plan plan;

    PinnedQuery(VersionedTable.PinnedVersion pinned, Plan plan) {
        this.pinned = pinned;
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

    /** The version this query pinned, and therefore the one it reads. */
    public int version() {
        return pinned.number();
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
        pinned.close();
    }
}
