package dk.itu.swandb;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Encodes and decodes primitive values into the on-disk format.
 *
 * The wire format is:
 *   LONG   -> 8-byte two's-complement, big-endian.
 *   DOUBLE -> 8-byte IEEE 754, big-endian.
 *   STRING -> uint16 length (2 bytes, big-endian) followed by UTF-8 bytes.
 *
 * Big-endian is chosen for human readability when inspecting a binary file;
 * {@link ByteBuffer} already defaults to big-endian, so no explicit
 * byte-order swap is needed on the read path.
 */
public final class ValueCodec {

    public static final int LONG_BYTES = 8;
    public static final int DOUBLE_BYTES = 8;
    public static final int STRING_LENGTH_BYTES = 2;
    public static final int MAX_STRING_LENGTH = 0xFFFF; // 65535

    private ValueCodec() {
    }

    /** Bytes a single value occupies when encoded. */
    public static int encodedSize(ColumnType type, Object value) {
        return switch (type) {
            case LONG -> LONG_BYTES;
            case DOUBLE -> DOUBLE_BYTES;
            case STRING -> STRING_LENGTH_BYTES + ((String) value).getBytes(StandardCharsets.UTF_8).length;
        };
    }

    /** Write a value into the buffer, advancing its position. */
    public static void encode(ByteBuffer buf, ColumnType type, Object value) {
        switch (type) {
            case LONG -> buf.putLong((Long) value);
            case DOUBLE -> buf.putDouble((Double) value);
            case STRING -> {
                byte[] bytes = ((String) value).getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_STRING_LENGTH)
                    throw new IllegalArgumentException(
                            "string length " + bytes.length + " exceeds max " + MAX_STRING_LENGTH);
                buf.putShort((short) bytes.length);
                buf.put(bytes);
            }
        }
    }

    /** Read a value from the buffer, advancing its position. */
    public static Object decode(ByteBuffer buf, ColumnType type) {
        return switch (type) {
            case LONG -> buf.getLong();
            case DOUBLE -> buf.getDouble();
            case STRING -> {
                int len = Short.toUnsignedInt(buf.getShort());
                byte[] bytes = new byte[len];
                buf.get(bytes);
                yield new String(bytes, StandardCharsets.UTF_8);
            }
        };
    }

    /** Encode a list of values for one column into its own byte array. */
    public static byte[] encodeColumn(ColumnType type, List<Object> values) {
        int total = 0;
        for (Object v : values) {
            total += encodedSize(type, v);
        }
        ByteBuffer buf = ByteBuffer.allocate(total);
        for (Object v : values) {
            encode(buf, type, v);
        }
        return buf.array();
    }

    /** Decode a column byte array into its values, in order. */
    public static List<Object> decodeColumn(ColumnType type, byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        List<Object> out = new ArrayList<>();
        while (buf.remaining() > 0) {
            out.add(decode(buf, type));
        }
        return out;
    }
}
