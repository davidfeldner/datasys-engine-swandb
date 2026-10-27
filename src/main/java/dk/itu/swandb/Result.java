package dk.itu.swandb;

import java.util.List;

/**
 * What one statement did: the figures its summary log line reports, plus the
 * rows a {@code SELECT} produced.
 *
 * <pre>
 * CREATE  version
 * COPY    version, rows, partitions        rows loaded, partitions appended
 * DELETE  version, rows, partitions, partitionsRewritten, partitionsCarried
 * SELECT  version, rows, partitionsRead, partitionsPruned
 * </pre>
 *
 * {@code version} is the version the statement committed, or for a
 * {@code SELECT} the version it pinned and read.
 */
public record Result(int version, int rows, int partitions,
                     int partitionsRewritten, int partitionsCarried,
                     int partitionsRead, int partitionsPruned,
                     List<Object[]> rowsOut) {

    public Result {
        rowsOut = List.copyOf(rowsOut);
    }

    /** {@code CREATE TABLE}: version 0, nothing done to any data. */
    public static Result created(int version) {
        return new Result(version, 0, 0, 0, 0, 0, 0, List.of());
    }

    /** {@code COPY}: rows loaded into results partitions appended at the end. */
    public static Result copied(int version, int rows, int partitions) {
        return new Result(version, rows, partitions, 0, 0, 0, 0, List.of());
    }

    /** {@code DELETE}: rows removed, partitions rewritten and carried over. */
    public static Result deleted(int version, int rowsDeleted, int rewritten, int carried) {
        return new Result(version, rowsDeleted, rewritten, rewritten, carried, 0, 0, List.of());
    }

    /** {@code SELECT}: the rows read out of a pinned version. */
    public static Result selected(int version, List<Object[]> rowsOut, int partitionsRead, int partitionsPruned) {
        return new Result(version, rowsOut.size(), 0, 0, 0, partitionsRead, partitionsPruned, rowsOut);
    }
}
