package com.example.starter.lidar.store;

import com.example.starter.lidar.model.PointDatum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 规范化点编解码测试。
 */
class PointCodecTest {

    @Test
    void encodesAndDecodesDeterministically() {
        List<PointDatum> points = List.of(
                new PointDatum(1.5, -2.25, 3.0, 100L),
                new PointDatum(-0.0, Double.NaN, Double.POSITIVE_INFINITY, Long.MIN_VALUE),
                new PointDatum(1e9, 1e-9, 0, Long.MAX_VALUE));

        byte[] a = PointCodec.encode(points);
        byte[] b = PointCodec.encode(points);
        assertEquals(a.length, b.length);
        // 同内容编码字节必须一致（校验和稳定性的基础）
        assertEquals(toHex(a), toHex(b));

        List<PointDatum> decoded = PointCodec.decode(a);
        assertEquals(points.size(), decoded.size());
        for (int i = 0; i < points.size(); i++) {
            assertEquals(Double.doubleToLongBits(points.get(i).x()),
                    Double.doubleToLongBits(decoded.get(i).x()));
            assertEquals(Double.doubleToLongBits(points.get(i).y()),
                    Double.doubleToLongBits(decoded.get(i).y()));
            assertEquals(Double.doubleToLongBits(points.get(i).z()),
                    Double.doubleToLongBits(decoded.get(i).z()));
            assertEquals(points.get(i).t(), decoded.get(i).t());
        }
    }

    @Test
    void emptyListRoundTrips() {
        byte[] encoded = PointCodec.encode(List.of());
        assertEquals(0, PointCodec.decode(encoded).size());
    }

    @Test
    void truncatedBytesAreCorruption() {
        byte[] encoded = PointCodec.encode(
                List.of(new PointDatum(1, 2, 3, 4), new PointDatum(5, 6, 7, 8)));
        byte[] truncated = java.util.Arrays.copyOf(encoded, encoded.length - 4);
        assertThrows(CorruptChunkException.class, () -> PointCodec.decode(truncated));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
