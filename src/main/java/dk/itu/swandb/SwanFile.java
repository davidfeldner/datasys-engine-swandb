package dk.itu.swandb;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

    /** Write the file from scratch; overwrites any existing file. */
    public static void write(Path path, List<ColumnSpec> schema, List<Partition> partitions)
            throws IOException {
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
                    channel.write(colBuf);
                }
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
                if (magic[i] != MAGIC[i]) {
                    throw new IllegalStateException("bad magic in " + path);
                }
            }
            byte version = header.get();
            if (version != VERSION) {
                throw new IllegalStateException(
                        "unsupported swan version " + version + " in " + path);
            }

            ByteBuffer meta = ByteBuffer.allocate(4 + schema.size());
            channel.read(meta);
            meta.flip();
            int columnCount = meta.getInt();
            if (columnCount != schema.size()) {
                throw new IllegalStateException(
                        "schema column count mismatch in " + path
                                + ": file=" + columnCount + " schema=" + schema.size());
            }
            ColumnType[] types = new ColumnType[columnCount];
            for (int i = 0; i < columnCount; i++) {
                types[i] = ColumnType.values()[meta.get()];
                if (types[i] != schema.get(i).type()) {
                    throw new IllegalStateException(
                            "schema type mismatch at column " + i + " in " + path);
                }
            }

            ByteBuffer pcBuf = ByteBuffer.allocate(4);
            channel.read(pcBuf);
            pcBuf.flip();
            int partitionCount = pcBuf.getInt();

            List<Partition> out = new ArrayList<>(partitionCount);
            for (int p = 0; p < partitionCount; p++) {
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
                        if (n < 0) {
                            throw new IllegalStateException("truncated file: " + path);
                        }
                    }
                    cols.add(ValueCodec.decodeColumn(types[c], bytes));
                }
                out.add(new Partition(rowCount, cols));
            }
            return out;
        }
    }
}
