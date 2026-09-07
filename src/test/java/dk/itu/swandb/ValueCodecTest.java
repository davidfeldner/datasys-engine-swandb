package dk.itu.swandb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValueCodecTest {

    @Test
    void longRoundTrip() {
        ByteBuffer buf = ByteBuffer.allocate(ValueCodec.LONG_BYTES);
        ValueCodec.encode(buf, ColumnType.LONG, -42L);
        buf.flip();
        assertEquals(-42L, ValueCodec.decode(buf, ColumnType.LONG));
    }

    @Test
    void doubleRoundTrip() {
        ByteBuffer buf = ByteBuffer.allocate(ValueCodec.DOUBLE_BYTES);
        ValueCodec.encode(buf, ColumnType.DOUBLE, 3.14159);
        buf.flip();
        assertEquals(3.14159, ValueCodec.decode(buf, ColumnType.DOUBLE));
    }

    @Test
    void stringRoundTrip() {
        String s = "Odense";
        ByteBuffer buf = ByteBuffer.allocate(ValueCodec.STRING_LENGTH_BYTES + s.getBytes().length);
        ValueCodec.encode(buf, ColumnType.STRING, s);
        buf.flip();
        assertEquals(s, ValueCodec.decode(buf, ColumnType.STRING));
    }

    @Test
    void emptyStringRoundTrip() {
        ByteBuffer buf = ByteBuffer.allocate(ValueCodec.STRING_LENGTH_BYTES);
        ValueCodec.encode(buf, ColumnType.STRING, "");
        buf.flip();
        assertEquals("", ValueCodec.decode(buf, ColumnType.STRING));
    }

    @Test
    void utf8StringRoundTrip() {
        String s = "Aa\u00e6\u00f8"; // Danish letters
        ByteBuffer buf = ByteBuffer.allocate(ValueCodec.encodedSize(ColumnType.STRING, s));
        ValueCodec.encode(buf, ColumnType.STRING, s);
        buf.flip();
        assertEquals(s, ValueCodec.decode(buf, ColumnType.STRING));
    }

    @Test
    void columnRoundTrip() {
        List<Object> longs = List.of(1L, 2L, 3L, -4L);
        byte[] encoded = ValueCodec.encodeColumn(ColumnType.LONG, longs);
        assertEquals(longs, ValueCodec.decodeColumn(ColumnType.LONG, encoded));
    }

    @Test
    void bigEndianLongFirstByteIsSign() {
        ByteBuffer buf = ByteBuffer.allocate(8);
        ValueCodec.encode(buf, ColumnType.LONG, 1L);
        buf.flip();
        assertEquals(0, buf.get(0)); // most significant byte of 1 is 0
    }
}
