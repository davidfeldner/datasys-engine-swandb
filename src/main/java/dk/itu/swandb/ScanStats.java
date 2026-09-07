package dk.itu.swandb;

/**
 * Observable per-call summary of how the partition scan was decided.
 */
public record ScanStats(int partitionsTotal, int partitionsRead, int partitionsPruned) {
}
