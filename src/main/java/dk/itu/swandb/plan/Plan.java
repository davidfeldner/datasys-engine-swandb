package dk.itu.swandb.plan;

import dk.itu.swandb.ScanStats;

/**
 * A planned operator tree plus the {@link ScanStats} the planner observed
 * while building it. Splitting the two lets {@code StorageEngine.select}
 * keep returning rows in the week 2 shape while still publishing how the
 * pruning decision went.
 */
public record Plan(Operator root, ScanStats stats) {
}
