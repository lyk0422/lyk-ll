package com.example.starter.pointcloud.service;

import com.example.starter.pointcloud.api.BoxQueryRequest;
import com.example.starter.pointcloud.api.CorridorQueryRequest;
import com.example.starter.pointcloud.api.QueryHitPoint;
import com.example.starter.pointcloud.api.QueryResponse;
import com.example.starter.pointcloud.api.RadiusQueryRequest;
import com.example.starter.pointcloud.api.ReindexResponse;
import com.example.starter.pointcloud.api.WriteChunkRequest;
import com.example.starter.pointcloud.api.WriteChunkResponse;
import com.example.starter.pointcloud.model.Compression;
import com.example.starter.pointcloud.model.Point;
import com.example.starter.pointcloud.support.H2TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PointCloudService} 集成测试：真实 H2（MODE=MySQL）索引 + 测试临时目录块文件。
 * 覆盖空间边界、稳定排序、损坏隔离、并发写入、重建增量与压缩回退。
 */
class PointCloudServiceTest {

    @TempDir
    Path tempDir;

    private H2TestSupport h2;
    private ChunkFileStore fileStore;
    private ChunkIndexRepository repository;
    private PointCloudService service;

    @BeforeEach
    void setUp() throws IOException {
        h2 = H2TestSupport.start();
        fileStore = new ChunkFileStore(tempDir);
        fileStore.init();
        repository = new ChunkIndexRepository(h2.dataSource());
        service = new PointCloudService(fileStore, repository);
    }

    @AfterEach
    void tearDown() {
        h2.shutdown();
    }

    private WriteChunkRequest writeRequest(String id, long version, String source,
                                           Compression compression, List<Point> points) {
        return new WriteChunkRequest(id, version, source, compression, points);
    }

    private BoxQueryRequest allCoveringBox() {
        return new BoxQueryRequest(-1000.0, -1000.0, -1000.0, 1000.0, 1000.0, 1000.0);
    }

    // ---------------------------------------------------------------------
    // 写入幂等与 409
    // ---------------------------------------------------------------------

    @Test
    void sameIdSameContentRetryReturnsOriginalResult() {
        List<Point> points = List.of(new Point(1, 1, 1, 10));
        WriteChunkResponse first = service.writeChunk(
                writeRequest("dup", 2, "src-A", Compression.NONE, points));
        WriteChunkResponse second = service.writeChunk(
                writeRequest("dup", 2, "src-A", Compression.GZIP, points));

        assertFalse(first.deduplicated());
        assertTrue(second.deduplicated());
        assertEquals(first.checksum(), second.checksum());
        assertEquals(first.version(), second.version());
        assertEquals(first.source(), second.source());
        assertEquals(1, repository.count());
    }

    @Test
    void sameIdDifferentContentReturnsConflict() {
        service.writeChunk(writeRequest("k", 1, "src", Compression.NONE,
                List.of(new Point(0, 0, 0, 1))));
        assertThrows(ChunkConflictException.class, () -> service.writeChunk(
                writeRequest("k", 2, "src", Compression.NONE,
                        List.of(new Point(0, 0, 0, 2)))));
        // 仅改来源也属于改参
        assertThrows(ChunkConflictException.class, () -> service.writeChunk(
                writeRequest("k", 1, "other-src", Compression.NONE,
                        List.of(new Point(0, 0, 0, 1)))));
        assertEquals(1, repository.count());
    }

    // ---------------------------------------------------------------------
    // 空间边界
    // ---------------------------------------------------------------------

    @Test
    void boxQueryIncludesBoundaryPointsAndReturnsVersionAndSource() {
        service.writeChunk(writeRequest("box", 5, "lidar-7", Compression.NONE, List.of(
                new Point(0, 0, 0, 1),     // 最小角
                new Point(10, 10, 10, 2),  // 最大角
                new Point(5, 5, 5, 3),    // 内部
                new Point(-0.001, 5, 5, 4) // 外部
        )));

        QueryResponse response = service.queryBox(
                new BoxQueryRequest(0.0, 0.0, 0.0, 10.0, 10.0, 10.0));

        assertEquals(3, response.points().size());
        assertTrue(response.quarantined().isEmpty());
        for (QueryHitPoint p : response.points()) {
            assertEquals("box", p.chunkId());
            assertEquals(5, p.chunkVersion());
            assertEquals("lidar-7", p.source());
        }
        // 边界点（最小角/最大角）必须包含
        assertTrue(response.points().stream().anyMatch(p -> p.x() == 0 && p.y() == 0));
        assertTrue(response.points().stream().anyMatch(p -> p.x() == 10 && p.y() == 10));
    }

    @Test
    void radiusQueryIncludesSphereSurface() {
        service.writeChunk(writeRequest("rad", 1, "s", Compression.NONE, List.of(
                new Point(3, 4, 0, 1),  // 距 (0,0,0) 恰为 5，球面上
                new Point(0, 0, 5, 2),  // 球面
                new Point(3.001, 4, 0, 3) // 略远
        )));

        QueryResponse response = service.queryRadius(
                new RadiusQueryRequest(0.0, 0.0, 0.0, 5.0));

        assertEquals(2, response.points().size());
    }

    @Test
    void corridorQueryIncludesWidthBoundary() {
        service.writeChunk(writeRequest("cor", 1, "s", Compression.NONE, List.of(
                new Point(5, 1.0, 0, 1),   // 恰在半宽边界
                new Point(5, -1.0, 0, 2),  // 另一侧边界
                new Point(5, 0, 0, 3),     // 中心
                new Point(5, 1.001, 0, 4)  // 边界外
        )));

        QueryResponse response = service.queryCorridor(new CorridorQueryRequest(
                List.of(new CorridorQueryRequest.Vec3Holder(0.0, 0.0, 0.0),
                        new CorridorQueryRequest.Vec3Holder(10.0, 0.0, 0.0)),
                1.0));

        assertEquals(3, response.points().size());
    }

    // ---------------------------------------------------------------------
    // 稳定排序
    // ---------------------------------------------------------------------

    @Test
    void resultsAreStablyOrderedByTimeChunkIdAndPointIndex() {
        // 块内点序刻意与时间顺序不一致，块间时间互相穿插
        service.writeChunk(writeRequest("A", 1, "s", Compression.NONE, List.of(
                new Point(0, 0, 0, 10),
                new Point(0, 0, 0, 30))));
        service.writeChunk(writeRequest("B", 1, "s", Compression.NONE, List.of(
                new Point(0, 0, 0, 20),
                new Point(0, 0, 0, 10))));

        QueryResponse response = service.queryBox(allCoveringBox());

        List<String> order = response.points().stream()
                .map(p -> p.timestampNanos() + "/" + p.chunkId() + "/" + p.pointIndex())
                .toList();
        // t=10 的两个点按 chunkId 排序；t=10 的 B 点保留其块内点序 1
        assertEquals(List.of("10/A/0", "10/B/1", "20/B/0", "30/A/1"), order);
    }

    // ---------------------------------------------------------------------
    // 压缩回退（端到端）
    // ---------------------------------------------------------------------

    @Test
    void gzipIsStoredForCompressibleChunkAndStillReadsBack() {
        List<Point> big = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            big.add(new Point(2.5, 2.5, 2.5, 1000 + i));
        }
        WriteChunkResponse written = service.writeChunk(
                writeRequest("gz", 1, "src", Compression.GZIP, big));
        assertEquals(Compression.GZIP, written.compression());
        assertEquals(Compression.GZIP, repository.find("gz").compression());

        byte[] fileBytes = assertDoesNotThrow(() -> fileStore.read(
                repository.find("gz").fileName()));
        assertEquals(1, fileBytes[4], "落盘文件应带 GZIP 标记");

        QueryResponse response = service.queryBox(allCoveringBox());
        assertEquals(400, response.points().size());
    }

    @Test
    void incompressibleRequestFallsBackToNoneWithSameChecksum() {
        // 固定种子伪随机点不可压缩，真实 GZIP 不会更小
        java.util.Random random = new java.util.Random(7);
        List<Point> randomPoints = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            randomPoints.add(new Point(
                    random.nextDouble() * 100 - 50,
                    random.nextDouble() * 100 - 50,
                    random.nextDouble() * 100 - 50,
                    random.nextLong()));
        }
        WriteChunkResponse viaGzip = service.writeChunk(
                writeRequest("rand-g", 1, "s", Compression.GZIP, randomPoints));
        assertEquals(Compression.NONE, viaGzip.compression());

        // 相同 chunkId 改以 NONE 重试：规范化内容一致，必须幂等返回同一校验和
        WriteChunkResponse viaNone = service.writeChunk(
                writeRequest("rand-g", 1, "s", Compression.NONE, randomPoints));
        assertTrue(viaNone.deduplicated());
        assertEquals(viaNone.checksum(), viaGzip.checksum());
    }

    // ---------------------------------------------------------------------
    // 损坏隔离
    // ---------------------------------------------------------------------

    @Test
    void corruptChunkIsQuarantinedAuditedExcludedAndRemovedFromIndex() throws IOException {
        service.writeChunk(writeRequest("bad", 1, "s", Compression.NONE, List.of(
                new Point(1, 1, 1, 1), new Point(2, 2, 2, 2))));
        String fileName = repository.find("bad").fileName();
        Path chunkFile = tempDir.resolve("chunks").resolve(fileName);

        // 直接篡改块文件内容（绕过服务），模拟磁盘损坏
        byte[] bytes = Files.readAllBytes(chunkFile);
        bytes[20] ^= 0xFF;
        Files.write(chunkFile, bytes);

        QueryResponse response = service.queryBox(allCoveringBox());

        assertTrue(response.points().isEmpty(), "损坏块的点必须从结果排除");
        assertEquals(1, response.quarantined().size());
        assertEquals("bad", response.quarantined().get(0).chunkId());
        assertFalse(Files.exists(chunkFile), "损坏块必须移出正式目录");
        assertNull(repository.find("bad"), "损坏块索引行必须删除");
        assertEquals(0, repository.count());

        try (Stream<Path> quarantined = Files.list(tempDir.resolve("quarantine"))) {
            assertEquals(1, quarantined.count());
        }
        String audit = Files.readString(tempDir.resolve("audit").resolve("audit.log"));
        assertTrue(audit.contains("QUARANTINE"));
        assertTrue(audit.contains("bad"));

        // 再次查询不再重复报告，且无异常
        QueryResponse second = service.queryBox(allCoveringBox());
        assertTrue(second.points().isEmpty());
        assertTrue(second.quarantined().isEmpty());
    }

    // ---------------------------------------------------------------------
    // 并发写入
    // ---------------------------------------------------------------------

    @Test
    void concurrentWritesOfSameChunkAreIdempotent() throws InterruptedException {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<WriteChunkResponse> responses = new ArrayList<>();
        try {
            List<java.util.concurrent.Future<WriteChunkResponse>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return service.writeChunk(writeRequest("race", 1, "s",
                            Compression.NONE, List.of(new Point(1, 1, 1, 1))));
                }));
            }
            start.countDown();
            for (var f : futures) {
                responses.add(assertDoesNotThrow(() -> f.get(30, TimeUnit.SECONDS)));
            }
        } finally {
            pool.shutdownNow();
        }

        long distinctChecksums = responses.stream().map(WriteChunkResponse::checksum).distinct().count();
        assertEquals(1, distinctChecksums);
        assertEquals(1, responses.stream().filter(r -> !r.deduplicated()).count(),
                "恰好一次真正发布");
        assertEquals(threads - 1, responses.stream().filter(WriteChunkResponse::deduplicated).count());
        assertEquals(1, repository.count());
    }

    @Test
    void concurrentWritesOfDistinctChunksAllBecomeQueryable() throws InterruptedException {
        int count = 12;
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    service.writeChunk(writeRequest("c-" + idx, 1, "s", Compression.NONE,
                            List.of(new Point(idx, idx, idx, 100 + idx))));
                }));
            }
            start.countDown();
            for (var f : futures) {
                assertDoesNotThrow(() -> f.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(count, repository.count());
        assertEquals(count, service.queryBox(allCoveringBox()).points().size());
    }

    // ---------------------------------------------------------------------
    // 无遗漏重建
    // ---------------------------------------------------------------------

    @Test
    void reindexRebuildsFromFilesAndKeepsIncrementalWrites() throws IOException {
        service.writeChunk(writeRequest("old-1", 1, "s", Compression.NONE,
                List.of(new Point(1, 1, 1, 10))));
        service.writeChunk(writeRequest("old-2", 1, "s", Compression.NONE,
                List.of(new Point(2, 2, 2, 20))));

        // 模拟索引损坏：丢弃内存索引，文件目录不变
        H2TestSupport freshH2 = H2TestSupport.start();
        ChunkIndexRepository freshRepo = new ChunkIndexRepository(freshH2.dataSource());
        assertEquals(0, freshRepo.count());

        List<String> incremental = new ArrayList<>();
        PointCloudService rebuilding = new PointCloudService(fileStore, freshRepo) {
            @Override
            protected void onBeforeProcessFile(String fileName) {
                // 仅在重建处理第一个文件时写入一个重建期间的新块
                if (incremental.isEmpty()) {
                    String id = "during-reindex-" + System.nanoTime();
                    writeChunk(writeRequest(id, 9, "s", Compression.NONE,
                            List.of(new Point(3, 3, 3, 30))));
                    incremental.add(id);
                }
            }
        };

        ReindexResponse result = rebuilding.reindex();
        try {
            assertEquals(3, result.scannedChunks(), "原有块 + 重建期间增量块");
            assertEquals(3, result.reindexed());
            assertTrue(result.quarantined().isEmpty());
            assertEquals(3, freshRepo.count());

            QueryResponse response = rebuilding.queryBox(allCoveringBox());
            assertEquals(3, response.points().size());
            assertTrue(freshRepo.find(incremental.get(0)) != null,
                    "重建期间写入的块在重建完成后必须仍可查到");
        } finally {
            freshH2.shutdown();
        }
    }

    @Test
    void writesThroughoutReindexAreNeverLost() throws Exception {
        // 重建开始前已有块
        service.writeChunk(writeRequest("before", 1, "s", Compression.NONE,
                List.of(new Point(0, 0, 0, 1))));

        H2TestSupport freshH2 = H2TestSupport.start();
        ChunkIndexRepository freshRepo = new ChunkIndexRepository(freshH2.dataSource());
        // 放慢重建：每个文件处理前稍作等待，拉长并发写入窗口
        PointCloudService rebuilding = new PointCloudService(fileStore, freshRepo) {
            @Override
            protected void onBeforeProcessFile(String fileName) {
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        int writers = 4;
        int perWriter = 10;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 1);
        CountDownLatch reindexDone = new CountDownLatch(1);
        try {
            // 重建线程
            var reindexFuture = pool.submit(() -> {
                ReindexResponse r = rebuilding.reindex();
                reindexDone.countDown();
                return r;
            });

            // 多个写入线程：重建进行中持续发布新块
            List<java.util.concurrent.Future<?>> writeFutures = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                int writer = w;
                writeFutures.add(pool.submit(() -> {
                    for (int i = 0; i < perWriter; i++) {
                        String id = "w" + writer + "-" + i;
                        rebuilding.writeChunk(writeRequest(id, 1, "s", Compression.NONE,
                                List.of(new Point(writer, i, 0, 100L + writer * perWriter + i))));
                    }
                }));
            }
            for (var f : writeFutures) {
                f.get(60, TimeUnit.SECONDS);
            }
            reindexFuture.get(60, TimeUnit.SECONDS);
            assertTrue(reindexDone.await(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        int expected = 1 + writers * perWriter;
        try {
            assertEquals(expected, freshRepo.count(),
                    "重建前已有块与重建全程写入的块均不得遗漏");
            assertEquals(expected, rebuilding.queryBox(allCoveringBox()).points().size());
        } finally {
            freshH2.shutdown();
        }
    }

    @Test
    void reindexIsIdempotentAndSurvivesCorruptFile() throws IOException {        service.writeChunk(writeRequest("good", 1, "s", Compression.NONE,
                List.of(new Point(1, 1, 1, 1))));
        service.writeChunk(writeRequest("goner", 1, "s", Compression.NONE,
                List.of(new Point(2, 2, 2, 2))));

        // 破坏其中一个块文件
        String fileName = repository.find("goner").fileName();
        Path p = tempDir.resolve("chunks").resolve(fileName);
        byte[] bytes = Files.readAllBytes(p);
        bytes[10] ^= 0x01;
        Files.write(p, bytes);

        ReindexResponse result = service.reindex();
        assertEquals(1, result.reindexed());
        assertEquals(List.of("goner"), result.quarantined());
        assertEquals(1, repository.count());

        // 再次重建结果稳定
        ReindexResponse again = service.reindex();
        assertEquals(1, again.scannedChunks());
        assertEquals(1, again.reindexed());
        assertTrue(again.quarantined().isEmpty());
    }
}
