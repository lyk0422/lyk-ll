package com.example.starter.pointcloud.service;

import com.example.starter.pointcloud.model.Compression;
import com.example.starter.pointcloud.model.Point;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ChunkCodec} 单元测试：规范化往返、GZIP 回退与损坏检测。
 */
class ChunkCodecTest {

    private static List<Point> samplePoints() {
        return List.of(
                new Point(1.5, -2.25, 3.0, 100L),
                new Point(4.0, 5.0, 6.0, 200L));
    }

    @Test
    void noneRoundTripPreservesAllFields() {
        List<Point> points = samplePoints();
        ChunkCodec.Encoded encoded = ChunkCodec.encode(
                "c-1", 7L, "lidar-A", points, Compression.NONE);

        assertEquals(Compression.NONE, encoded.compression());
        assertEquals(64, encoded.checksum().length());

        ChunkCodec.Decoded decoded = ChunkCodec.decode(encoded.fileBytes());
        assertEquals("c-1", decoded.chunkId());
        assertEquals(7L, decoded.version());
        assertEquals("lidar-A", decoded.source());
        assertEquals(points, decoded.points());
        assertEquals(encoded.checksum(), decoded.checksum());
    }

    @Test
    void canonicalEncodingIsStableAcrossCompressionRequests() {
        ChunkCodec.Encoded none = ChunkCodec.encode(
                "c-2", 1L, "src", samplePoints(), Compression.NONE);
        ChunkCodec.Encoded gzip = ChunkCodec.encode(
                "c-2", 1L, "src", samplePoints(), Compression.GZIP);
        // 回退与否，规范化内容一致，校验和必须一致
        assertEquals(none.checksum(), gzip.checksum());
    }

    @Test
    void gzipIsUsedWhenCompressionShrinksData() {
        List<Point> big = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            big.add(new Point(10.0, 10.0, 10.0, 1000L));
        }
        ChunkCodec.Encoded encoded = ChunkCodec.encode(
                "big", 3L, "radar-X", big, Compression.GZIP);
        assertEquals(Compression.GZIP, encoded.compression());

        ChunkCodec.Decoded decoded = ChunkCodec.decode(encoded.fileBytes());
        assertEquals("big", decoded.chunkId());
        assertEquals(500, decoded.points().size());
        assertEquals(new Point(10.0, 10.0, 10.0, 1000L), decoded.points().get(499));
    }

    @Test
    void gzipFallsBackToNoneWhenResultNotSmaller() {
        List<Point> tiny = List.of(new Point(0.0, 0.0, 0.0, 1L));
        // 注入“压缩后与原数据等长”的 GZIP 结果：不小于原数据必须回退 NONE
        ChunkCodec.Encoded gzip = ChunkCodec.encode(
                "tiny", 1L, "s", tiny, Compression.GZIP, data -> data.clone());
        ChunkCodec.Encoded none = ChunkCodec.encode(
                "tiny", 1L, "s", tiny, Compression.NONE);

        assertEquals(Compression.NONE, gzip.compression());
        assertEquals(none.checksum(), gzip.checksum());
        assertArrayEquals(none.fileBytes(), gzip.fileBytes());

        ChunkCodec.Decoded decoded = ChunkCodec.decode(gzip.fileBytes());
        assertEquals(tiny, decoded.points());
    }

    @Test
    void gzipFallsBackToNoneForIncompressibleRealData() {
        // 固定种子的伪随机点：不可压缩，真实 GZIP 结果不小于原数据
        java.util.Random random = new java.util.Random(42);
        List<Point> randomPoints = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            randomPoints.add(new Point(
                    random.nextDouble() * 1000 - 500,
                    random.nextDouble() * 1000 - 500,
                    random.nextDouble() * 1000 - 500,
                    random.nextLong()));
        }
        ChunkCodec.Encoded encoded = ChunkCodec.encode(
                "random", 1L, "s", randomPoints, Compression.GZIP);
        assertEquals(Compression.NONE, encoded.compression());
        assertEquals(randomPoints, ChunkCodec.decode(encoded.fileBytes()).points());
    }

    @Test
    void gzipFailureFallsBackToNoneWithIdenticalContentAndChecksum() {
        List<Point> points = samplePoints();
        ChunkCodec.Encoded fallback = ChunkCodec.encode(
                "c-3", 2L, "src", points, Compression.GZIP,
                data -> {
                    throw new RuntimeException("模拟 GZIP 压缩失败");
                });
        ChunkCodec.Encoded none = ChunkCodec.encode(
                "c-3", 2L, "src", points, Compression.NONE);

        assertEquals(Compression.NONE, fallback.compression());
        assertEquals(none.checksum(), fallback.checksum());
        assertArrayEquals(none.fileBytes(), fallback.fileBytes());
        assertEquals(points, ChunkCodec.decode(fallback.fileBytes()).points());
    }

    @Test
    void tamperedPayloadFailsChecksum() {
        ChunkCodec.Encoded encoded = ChunkCodec.encode(
                "c-4", 1L, "src", samplePoints(), Compression.NONE);
        byte[] tampered = encoded.fileBytes().clone();
        tampered[10] ^= 0x01;
        assertThrows(CorruptChunkException.class, () -> ChunkCodec.decode(tampered));
    }

    @Test
    void tamperedChecksumFails() {
        ChunkCodec.Encoded encoded = ChunkCodec.encode(
                "c-5", 1L, "src", samplePoints(), Compression.NONE);
        byte[] tampered = encoded.fileBytes().clone();
        tampered[tampered.length - 1] ^= 0x01;
        assertThrows(CorruptChunkException.class, () -> ChunkCodec.decode(tampered));
    }

    @Test
    void badMagicIsRejected() {
        ChunkCodec.Encoded encoded = ChunkCodec.encode(
                "c-6", 1L, "src", samplePoints(), Compression.NONE);
        byte[] bad = encoded.fileBytes().clone();
        bad[0] = 'X';
        assertThrows(CorruptChunkException.class, () -> ChunkCodec.decode(bad));
    }

    @Test
    void truncatedFileIsRejected() {
        ChunkCodec.Encoded encoded = ChunkCodec.encode(
                "c-7", 1L, "src", samplePoints(), Compression.NONE);
        byte[] truncated = new byte[encoded.fileBytes().length - 10];
        System.arraycopy(encoded.fileBytes(), 0, truncated, 0, truncated.length);
        assertThrows(CorruptChunkException.class, () -> ChunkCodec.decode(truncated));
    }
}
