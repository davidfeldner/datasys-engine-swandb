package dk.itu.swandb;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dk.itu.swandb.enums.ColumnType;

/**
 * Reader/writer for the {@code .swan} binary file format. Layout:
 *
 * <pre>
 *   magic         6 bytes  "SWANDB"
 *   version       1 byte   format version (currently 1)
 *   reserved     16 bytes  zeroed, reserved for future metadata
 *   columnCount   4 bytes  big-endian int
 *   columnTypes   N bytes  one byte per column (0=STRING,1=LONG,2=DOUBLE)
 *   partitionCount 4 bytes big-endian int
 *   partitions    repeated partitionCount times:
 *      rowCount   4 bytes  big-endian int
 *      columns    repeated columnCount times:
 *          length 4 bytes  big-endian int
 *          data   N bytes  {@link ValueCodec}-encoded column bytes
 * </pre>
 *
 * The format is row-wise on disk: each partition holds {@code rowCount}
 * complete rows. Columns are stored per partition as flat arrays of
 * encoded values, which is convenient for the per-column min/max the
 * writer computes during ingestion.
 */
public final class SwanFile {

    public static final byte[] MAGIC = {'S', 'W', 'A', 'N', 'D', 'B'};
    public static final byte VERSION = 1;
    public static final int RESERVED_BYTES = 16;
    public static final int HEADER_BYTES = MAGIC.length + 1 + RESERVED_BYTES;

    public static final String EXTENSION = ".swan";

    /** One partition's worth of rows, kept as parallel arrays per column. */
    public static final class Partition {
        public final int rowCount;
        public final List<List<Object>> columnValues; // one list per column

        public Partition(int rowCount, List<List<Object>> columnValues) {
            this.rowCount = rowCount;
            this.columnValues = columnValues;
        }
    }

    private SwanFile() {
    }

    /** Write the file from scratch; overwrites any existing file. Returns the byte offsets of each partition. */
    public static List<Long> write(Path path, List<ColumnSpec> schema, List<Partition> partitions)
            throws IOException {
        List<Long> partitionOffsets = new ArrayList<>(partitions.size());
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw");
             FileChannel channel = raf.getChannel()) {
            channel.truncate(0);

            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
            header.put(MAGIC);
            header.put(VERSION);
            byte[] reserved = new byte[RESERVED_BYTES];
            header.put(reserved);
            header.flip();
            channel.write(header);

            ByteBuffer meta = ByteBuffer.allocate(4 + schema.size());
            meta.putInt(schema.size());
            for (ColumnSpec spec : schema) {
                meta.put((byte) spec.type().ordinal());
            }
            meta.flip();
            channel.write(meta);

            ByteBuffer pcBuf = ByteBuffer.allocate(4);
            pcBuf.putInt(partitions.size());
            pcBuf.flip();
            channel.write(pcBuf);

            for (Partition p : partitions) {
                // Record the offset of this partition (before writing rowCount)
                partitionOffsets.add(channel.position());
                writePartition(channel, schema, p);
            }
        }
        return partitionOffsets;
    }

    /**
     * Append partitions to an existing file: keep the first {@code keepBytes}
     * bytes of partition data, set the header's partition count to the number
     * of partitions those bytes hold plus the new ones, and write the new
     * partitions after them.
     *
     * <p>The count sits in the header, so a commit cannot literally append:
     * it truncates the file to the bytes it keeps, rewrites the count and
     * appends. Truncation is what makes it safe to drop reclamation — the
     * bytes below {@code keepBytes} are never rewritten, so every offset a
     * live version recorded there still points at the same partition.
     *
     * @param keepEnd the absolute byte the kept partitions end at, which is
     *                where the file is truncated to; bytes past it are those
     *                no live version points at any more.
     * @param keptPartitions how many partitions the bytes before
     *                {@code keepEnd} hold, so the header count can be updated.
     * @return the byte offsets of the appended partitions, in order.
     */
    public static List<Long> append(Path path, List<ColumnSpec> schema, List<Partition> partitions,
            long keepEnd, int keptPartitions) throws IOException {
        // metadataBytes already counts the header.
        long metadataEnd = metadataBytes(schema.size());
        if (keepEnd < metadataEnd)
            throw new IllegalArgumentException("keepEnd must be >= " + metadataEnd + ": " + keepEnd);
        if (keptPartitions < 0)
            throw new IllegalArgumentException("keptPartitions must be >= 0: " + keptPartitions);
        List<Long> partitionOffsets = new ArrayList<>(partitions.size());
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw");
             FileChannel channel = raf.getChannel()) {
            if (channel.size() < keepEnd)
                throw new IllegalStateException(
                        "cannot keep bytes up to " + keepEnd + " in " + path + ": the file is shorter than that");
            checkHeader(channel, path, schema);
            channel.truncate(keepEnd);

            ByteBuffer count = ByteBuffer.allocate(4);
            count.putInt(keptPartitions + partitions.size());
            count.flip();
            channel.position(partitionCountOffset(schema.size()));
            channel.write(count);

            channel.position(keepEnd);
            for (Partition p : partitions) {
                partitionOffsets.add(channel.position());
                writePartition(channel, schema, p);
            }
        }
        return partitionOffsets;
    }

    /** The partition count recorded in the file header. */
    public static int readPartitionCount(Path path, int columnCount) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r");
             FileChannel channel = raf.getChannel()) {
            ByteBuffer count = ByteBuffer.allocate(4);
            channel.position(partitionCountOffset(columnCount));
            channel.read(count);
            count.flip();
            return count.getInt();
        }
    }

    /**
     * Layout after the fixed header, as {@link #write} lays it down:
     * <pre>
     *   columnCount   4 bytes
     *   columnTypes   1 byte per column
     *   partitionCount 4 bytes
     *   partitions...
     * </pre>
     */
    public static long columnCountOffset() {
        return HEADER_BYTES;
    }

    /** The byte at which the 4-byte partition count sits: after the column types. */
    public static long partitionCountOffset(int columnCount) {
        return HEADER_BYTES + 4 + columnCount;
    }

    /** The byte at which partitions start: after the partition count. */
    public static long metadataBytes(int columnCount) {
        return partitionCountOffset(columnCount) + 4;
    }

    private static void checkHeader(FileChannel channel, Path path, List<ColumnSpec> schema)
            throws IOException {
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        channel.position(0);
        while (header.hasRemaining() && channel.read(header) > 0) {
            // read the whole fixed header before the column count
        }
        header.flip();
        byte[] magic = new byte[MAGIC.length];
        header.get(magic);
        for (int i = 0; i < MAGIC.length; i++) {
            if (magic[i] != MAGIC[i]) throw new IllegalStateException("bad magic in " + path);
        }
        byte version = header.get();
        if (version != VERSION)
            throw new IllegalStateException("unsupported swan version " + version + " in " + path);
        ByteBuffer meta = ByteBuffer.allocate(4);
        channel.position(columnCountOffset());
        channel.read(meta);
        meta.flip();
        int columnCount = meta.getInt();
        if (columnCount != schema.size())
            throw new IllegalStateException(
                    "schema column count mismatch in " + path + ": file=" + columnCount + " schema=" + schema.size());
    }

    private static void writePartition(FileChannel channel, List<ColumnSpec> schema, Partition p)
            throws IOException {
        ByteBuffer rcBuf = ByteBuffer.allocate(4);
        rcBuf.putInt(p.rowCount);
        rcBuf.flip();
        channel.write(rcBuf);

        for (int c = 0; c < schema.size(); c++) {
            ColumnType type = schema.get(c).type();
            List<Object> values = p.columnValues.get(c);
            byte[] encoded = ValueCodec.encodeColumn(type, values);

            ByteBuffer lenBuf = ByteBuffer.allocate(4);
            lenBuf.putInt(encoded.length);
            lenBuf.flip();
            channel.write(lenBuf);

            ByteBuffer colBuf = ByteBuffer.wrap(encoded);
            while (colBuf.hasRemaining()) {
                channel.write(colBuf);
            }
        }
    }

    /** Read all partitions from a file, returning them in on-disk order. */
    public static List<Partition> readAll(Path path, List<ColumnSpec> schema)
            throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r");
             FileChannel channel = raf.getChannel()) {

            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
            channel.read(header);
            header.flip();
            byte[] magic = new byte[MAGIC.length];
            header.get(magic);
            for (int i = 0; i < MAGIC.length; i++) {
                if (magic[i] != MAGIC[i]) throw new IllegalStateException("bad magic in " + path);
            }
            byte version = header.get();
            if (version != VERSION)
                throw new IllegalStateException(
                        "unsupported swan version " + version + " in " + path);

            ByteBuffer meta = ByteBuffer.allocate(4 + schema.size());
            channel.read(meta);
            meta.flip();
            int columnCount = meta.getInt();
            if (columnCount != schema.size())
                throw new IllegalStateException(
                        "schema column count mismatch in " + path
                                + ": file=" + columnCount + " schema=" + schema.size());
            ColumnType[] types = new ColumnType[columnCount];
            for (int i = 0; i < columnCount; i++) {
                types[i] = ColumnType.values()[meta.get()];
                if (types[i] != schema.get(i).type())
                    throw new IllegalStateException(
                            "schema type mismatch at column " + i + " in " + path);
            }

            ByteBuffer pcBuf = ByteBuffer.allocate(4);
            channel.read(pcBuf);
            pcBuf.flip();
            int partitionCount = pcBuf.getInt();

            List<Partition> out = new ArrayList<>(partitionCount);
            for (int p = 0; p < partitionCount; p++) {
                out.add(readPartitionInternal(channel, columnCount, types));
            }
            return out;
        }
    }

    /** Read a single partition at the given byte offset. */
    public static Partition readPartition(Path path, List<ColumnSpec> schema, long offset)
            throws IOException {
        if (offset < 0)
            throw new IllegalArgumentException(
                    "partition offset must be >= 0 in " + path + ": " + offset
                            + "; a catalog without offsets must be read with readAll");
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r");
             FileChannel channel = raf.getChannel()) {
            channel.position(offset);
            return readPartitionInternal(channel, schema.size(), getTypesFromSchema(schema));
        }
    }

    private static ColumnType[] getTypesFromSchema(List<ColumnSpec> schema) {
        ColumnType[] types = new ColumnType[schema.size()];
        for (int i = 0; i < schema.size(); i++) {
            types[i] = schema.get(i).type();
        }
        return types;
    }

    private static Partition readPartitionInternal(FileChannel channel, int columnCount, ColumnType[] types)
            throws IOException {
        ByteBuffer rcBuf = ByteBuffer.allocate(4);
        channel.read(rcBuf);
        rcBuf.flip();
        int rowCount = rcBuf.getInt();

        List<List<Object>> cols = new ArrayList<>(columnCount);
        for (int c = 0; c < columnCount; c++) {
            ByteBuffer lenBuf = ByteBuffer.allocate(4);
            channel.read(lenBuf);
            lenBuf.flip();
            int len = lenBuf.getInt();

            byte[] bytes = new byte[len];
            ByteBuffer wrap = ByteBuffer.wrap(bytes);
            while (wrap.hasRemaining()) {
                int n = channel.read(wrap);
                if (n < 0) throw new IllegalStateException("truncated file");
            }
            cols.add(ValueCodec.decodeColumn(types[c], bytes));
        }
        return new Partition(rowCount, cols);
    }
}
