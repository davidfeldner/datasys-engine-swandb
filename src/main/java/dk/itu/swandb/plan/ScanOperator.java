package dk.itu.swandb.plan;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import dk.itu.swandb.Catalog;
import dk.itu.swandb.ColumnSpec;
import dk.itu.swandb.SwanFile;

/**
 * Reads exactly the partitions it is handed and returns every row in them,
 * in partition order. It never sees the predicate and makes no decisions:
 * the planner has already pruned the list, so a fully pruned query reaches
 * this operator with an empty list and reads nothing.
 *
 * <p>The catalog records each partition's byte offset so the scan seeks
 * straight to the partitions it was given; a partition the planner pruned
 * is never touched. Catalogs written before offset tracking fall back to a
 * sequential read, as described in {@code docs/io-pruning.md}.
 */
public final class ScanOperator implements Operator {

    private final Path dataPath;
    private final List<ColumnSpec> schema;
    private final List<Catalog.PartitionEntry> partitions;

    private int partitionIndex;
    private SwanFile.Partition current;
    private int rowInPartition;

    public ScanOperator(Path dataPath, List<ColumnSpec> schema,
            List<Catalog.PartitionEntry> partitions) {
        this.dataPath = dataPath;
        this.schema = List.copyOf(schema);
        this.partitions = List.copyOf(partitions);
    }

    /** The partitions this scan was handed, in order. */
    public List<Catalog.PartitionEntry> partitions() {
        return partitions;
    }

    @Override
    public void open() {
        partitionIndex = 0;
        current = null;
        rowInPartition = 0;
    }

    @Override
    public Object[] next() {
        while (true) {
            if (current == null) {
                if (partitionIndex >= partitions.size())
                    return null;
                current = readPartition(partitions.get(partitionIndex++));
                rowInPartition = 0;
            }
            if (rowInPartition >= current.rowCount) {
                current = null;
                continue;
            }
            int r = rowInPartition++;
            Object[] row = new Object[schema.size()];
            for (int c = 0; c < schema.size(); c++) {
                row[c] = current.columnValues.get(c).get(r);
            }
            return row;
        }
    }

    @Override
    public void close() {
        current = null;
    }

    private SwanFile.Partition readPartition(Catalog.PartitionEntry entry) {
        try {
            long partitionOffset = entry.offset();
            // Seek straight to the live partition; pruned partitions are never
            // touched. Fallback for catalogs written before offsets were tracked.
            if (partitionOffset < 0)
                return SwanFile.readAll(dataPath, schema).get(entry.index());
            return SwanFile.readPartition(dataPath, schema, partitionOffset);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + dataPath, e);
        }
    }
}
