package com.example.starter.lidar.store;

import com.example.starter.lidar.model.BBox;
import com.example.starter.lidar.model.ChunkMetadata;
import com.example.starter.lidar.model.Compression;
import com.example.starter.lidar.model.PointDatum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 块文件存储测试：原子发布、GZIP 压缩/回退、读取校验与损坏隔离、目录扫描重建。
 */
class ChunkFileStoreTest {

    private ChunkFileStore newStore(Path temp) throws IOException {
        ChunkFileStore store = new ChunkFileStore(temp);
        store.init();
        return store;
    }

    private List<PointDatum> repetitivePoints(int count) {
        List<PointDatum> points = new ArrayList<>(count);
        // 高度重复数据：二进制编码可被 GZIP 显著压缩
        for (int i = 0; i < count; i++) {
            points.add(new PointDatum(1.0, 2.0, 3.0, 1000L + (i % 4)));
        }
        return points;
    }

    private List<PointDatum> randomPoints(int count) {
        Random rnd = new Random(42);
        List<PointDatum> points = new ArrayList<>(count);
        // 随机双精度不可压缩，加 GZIP 头尾后通常不小于原文
        for (int i = 0; i < count; i++) {
            points.add(new PointDatum(rnd.nextDouble(), rnd.nextDouble(),
                    rnd.nextDouble(), rnd.nextLong()));
        }
        return points;
    }

    @Test
    void publishesNoneAndRoundTrips(@TempDir Path temp) throws IOException {
        ChunkFileStore store = newStore(temp);
        List<PointDatum> points = randomPoints(20);
        byte[] normalized = PointCodec.encode(points);
        String checksum = ChunkFileStore.sha256Hex(normalized);

        ChunkFileStore.StoredBlob blob = store.publish(
                "c-none", 1, "lidar-A", points, normalized, checksum, Compression.NONE);
        assertEquals(Compression.NONE, blob.compression());
        assertTrue(Files.exists(store.getChunksDir().resolve(blob.fileName())));
        // 发布后临时目录不得残留临时文件
        try (var tmpFiles = Files.list(store.getRootDir().resolve("tmp"))) {
            assertEquals(0, tmpFiles.count());
        }

        ChunkMetadata md = metadata("c-none", 1, "lidar-A", points, blob, checksum);
        ChunkFileStore.LoadedBlob loaded = store.load(md);
        assertArrayEquals(normalized, loaded.normalized());
        assertEquals(points, PointCodec.decode(loaded.normalized()));
    }

    @Test
    void gzipIsUsedWhenSmallerAndChecksumStaysOfNormalizedContent(@TempDir Path temp) throws IOException {
        ChunkFileStore store = newStore(temp);
        List<PointDatum> points = repetitivePoints(2000);
        byte[] normalized = PointCodec.encode(points);
        String checksum = ChunkFileStore.sha256Hex(normalized);

        ChunkFileStore.StoredBlob blob = store.publish(
                "c-gz", 7, "lidar-G", points, normalized, checksum, Compression.GZIP);
        assertEquals(Compression.GZIP, blob.compression());
        assertTrue(blob.storedSize() < normalized.length,
                "可压缩数据应产生更小文件");

        ChunkMetadata md = metadata("c-gz", 7, "lidar-G", points, blob, checksum);
        ChunkFileStore.LoadedBlob loaded = store.load(md);
        // 即使落盘为 GZIP，校验和针对的仍是规范化未压缩内容
        assertEquals(checksum, ChunkFileStore.sha256Hex(loaded.normalized()));
        assertArrayEquals(normalized, loaded.normalized());
    }

    @Test
    void fallsBackToNoneWhenGzipNotSmallerWithSameChecksum(@TempDir Path temp) throws IOException {
        ChunkFileStore store = newStore(temp);
        // 仅 2 个随机点：56 字节高熵载荷加 GZIP 固定头尾后必然不小于原文
        List<PointDatum> points = randomPoints(2);
        byte[] normalized = PointCodec.encode(points);
        String checksum = ChunkFileStore.sha256Hex(normalized);

        ChunkFileStore.StoredBlob blob = store.publish(
                "c-incompressible", 2, "src", points, normalized, checksum, Compression.GZIP);
        // 压缩后不小于原文时回退 NONE；回退后读取内容与校验和必须与请求一致
        assertEquals(Compression.NONE, blob.compression());

        ChunkMetadata md = metadata("c-incompressible", 2, "src", points, blob, checksum);
        ChunkFileStore.LoadedBlob loaded = store.load(md);
        assertArrayEquals(normalized, loaded.normalized());
        assertEquals(checksum, ChunkFileStore.sha256Hex(loaded.normalized()));
    }

    @Test
    void corruptedPayloadFailsChecksumAndIsQuarantined(@TempDir Path temp) throws Exception {
        ChunkFileStore store = newStore(temp);
        List<PointDatum> points = randomPoints(500);
        byte[] normalized = PointCodec.encode(points);
        String checksum = ChunkFileStore.sha256Hex(normalized);
        ChunkFileStore.StoredBlob blob = store.publish(
                "c-bad", 1, "src", points, normalized, checksum, Compression.NONE);

        Path file = store.getChunksDir().resolve(blob.fileName());
        byte[] bytes = Files.readAllBytes(file);
        // 翻转载荷区一个字节（文件尾，避开头部），使规范化内容 SHA-256 不匹配
        int idx = bytes.length - 24;
        bytes[idx] ^= 0x01;
        Files.write(file, bytes);

        ChunkMetadata md = metadata("c-bad", 1, "src", points, blob, checksum);
        assertThrows(CorruptChunkException.class, () -> store.load(md));

        Path moved = store.quarantine(blob.fileName());
        assertTrue(Files.exists(moved));
        assertTrue(Files.notExists(file));
        try (var chunkFiles = Files.list(store.getChunksDir())) {
            assertEquals(0, chunkFiles.count());
        }
    }

    @Test
    void garbageHeaderIsCorruption(@TempDir Path temp) throws Exception {
        ChunkFileStore store = newStore(temp);
        Path bogus = store.getChunksDir().resolve("bogus.lpc");
        Files.write(bogus, new byte[]{0, 1, 2, 3, 4, 5, 6, 7});

        List<ChunkFileStore.ScanEntry> entries = store.scanAll();
        assertEquals(1, entries.size());
        ChunkFileStore.ScanBad bad = assertInstanceOf(ChunkFileStore.ScanBad.class, entries.get(0));
        assertEquals("bogus.lpc", bad.fileName());

        Path moved = store.quarantine(bad.fileName());
        assertTrue(Files.exists(moved));
    }

    @Test
    void scanAllRebuildsMetadataFromFiles(@TempDir Path temp) throws Exception {
        ChunkFileStore store = newStore(temp);
        List<PointDatum> points = repetitivePoints(300);
        byte[] normalized = PointCodec.encode(points);
        String checksum = ChunkFileStore.sha256Hex(normalized);
        ChunkFileStore.StoredBlob blob = store.publish(
                "c-scan", 9, "scan-src", points, normalized, checksum, Compression.GZIP);

        List<ChunkFileStore.ScanEntry> entries = store.scanAll();
        assertEquals(1, entries.size());
        ChunkFileStore.ScanOk ok = assertInstanceOf(ChunkFileStore.ScanOk.class, entries.get(0));
        assertEquals("c-scan", ok.metadata().chunkId());
        assertEquals(9, ok.metadata().version());
        assertEquals("scan-src", ok.metadata().source());
        assertEquals(points.size(), ok.metadata().pointCount());
        assertEquals(blob.compression(), ok.metadata().compression());
        assertEquals(checksum, ok.metadata().checksum());
    }

    private static ChunkMetadata metadata(String id, long version, String source,
                                          List<PointDatum> points,
                                          ChunkFileStore.StoredBlob blob, String checksum) {
        long start = points.get(0).t();
        long end = start;
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (PointDatum p : points) {
            start = Math.min(start, p.t());
            end = Math.max(end, p.t());
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            minZ = Math.min(minZ, p.z());
            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
            maxZ = Math.max(maxZ, p.z());
        }
        return new ChunkMetadata(id, version, source, start, end,
                new BBox(minX, minY, minZ, maxX, maxY, maxZ), points.size(),
                blob.compression(), checksum, blob.fileName(), blob.storedSize(), Instant.now());
    }
}
