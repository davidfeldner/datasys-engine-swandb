package dk.itu.swandb;

/**
 * Observable per-call summary of how the partition scan was decided.
 */
public record ScanStats(int partitionsTotal, int partitionsRead, int partitionsPruned) {

    /**
     * The concatenation of two tables' figures, for a plan that scans more
     * than one table. Every table is pruned on its own, so the totals and the
     * per-table decisions simply add up.
     */
    public ScanStats plus(ScanStats other) {
        return new ScanStats(partitionsTotal + other.partitionsTotal,
                partitionsRead + other.partitionsRead,
                partitionsPruned + other.partitionsPruned);
    }
}
