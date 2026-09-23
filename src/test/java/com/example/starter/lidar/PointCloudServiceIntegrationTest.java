package com.example.starter.lidar;

import com.example.starter.lidar.audit.AuditRepository;
import com.example.starter.lidar.config.LidarStorageProperties;
import com.example.starter.lidar.index.ChunkIndexRepository;
import com.example.starter.lidar.model.BBox;
import com.example.starter.lidar.model.Compression;
import com.example.starter.lidar.model.PointDatum;
import com.example.starter.lidar.model.QueryType;
import com.example.starter.lidar.model.RebuildResponse;
import com.example.starter.lidar.model.SpatialQueryRequest;
import com.example.starter.lidar.model.SpatialQueryResponse;
import com.example.starter.lidar.model.Vec3;
import com.example.starter.lidar.model.WriteChunkRequest;
import com.example.starter.lidar.model.WriteChunkResponse;
import com.example.starter.lidar.service.PointCloudService;
import com.example.starter.lidar.store.ChunkFileStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端集成测试：H2(MODE=MySQL) 内存索引 + 测试临时目录块文件。
 * 覆盖写块/幂等/409、三类空间查询边界与稳定排序、损坏隔离、并发写入、重建期间增量不遗漏。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.main.allow-bean-definition-overriding=true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PointCloudServiceIntegrationTest {

    /** 全部测试共享的块文件临时根目录（静态初始化，先于 Spring 上下文创建）。 */
    static Path sharedTemp;

    /** 重建文件快照取得/增量写入完成的同步闸，用于确定性复现“重建期间写入”。 */
    static volatile CountDownLatch rebuildSnapshotTaken;
    static volatile CountDownLatch incrementalWritesDone;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        if (sharedTemp == null) {
            try {
                sharedTemp = Files.createTempDirectory("lidar-it-");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:lidar_it;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("lidar.storage.root-dir", () -> sharedTemp.toString());
    }

    @AfterAll
    static void deleteTempRoot() throws IOException {
        if (sharedTemp != null) {
            deleteRecursively(sharedTemp);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // Windows 上偶发的文件句柄延迟释放可接受
                        }
                    });
        }
    }

    /** 在文件快照取得后暂停，等待测试主线程完成重建窗口内的增量写入。 */
    static class ControllableChunkFileStore extends ChunkFileStore {
        ControllableChunkFileStore(Path rootDir) throws IOException {
            super(rootDir);
            init();
        }

        @Override
        protected void onSnapshotTaken() {
            CountDownLatch taken = rebuildSnapshotTaken;
            CountDownLatch done = incrementalWritesDone;
            if (taken != null) {
                taken.countDown();
                if (done != null) {
                    try {
                        assertTrue(done.await(10, TimeUnit.SECONDS),
                                "重建窗口内的增量写入未在超时前完成");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
            }
        }
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class TestStoreConfig {
        @Bean
        @Primary
        ChunkFileStore chunkFileStore(LidarStorageProperties props) throws IOException {
            return new ControllableChunkFileStore(props.rootDir());
        }
    }

    @Autowired
    private PointCloudService service;
    @Autowired
    private ChunkIndexRepository index;
    @Autowired
    private AuditRepository audit;
    @Autowired
    private ChunkFileStore fileStore;
    @Autowired
    private JdbcTemplate jdbc;
    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate rest;

    @BeforeAll
    void initLatches() {
        rebuildSnapshotTaken = null;
        incrementalWritesDone = null;
    }

    @BeforeEach
    void resetState() throws IOException {
        jdbc.update("DELETE FROM lidar_chunk");
        jdbc.update("DELETE FROM lidar_audit");
        clearDir(fileStore.getChunksDir());
        clearDir(fileStore.getQuarantineDir());
        clearDir(sharedTemp.resolve("tmp"));
    }

    private static void clearDir(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var paths = Files.list(dir)) {
            for (Path p : paths.toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    // ------------------------------------------------------------------
    // 写入：幂等、409、HTTP 语义
    // ------------------------------------------------------------------

    @Test
    void writeIsIdempotentForSameContentAndConflictsOnChangedParams() {
        String id = "w-1";
        List<PointDatum> points = List.of(
                new PointDatum(0, 0, 0, 10), new PointDatum(1, 1, 1, 20));
        WriteChunkRequest req = new WriteChunkRequest(id, 1, "src-A", Compression.NONE, points);

        WriteChunkResponse first = service.write(req);
        assertFalse(first.deduplicated());
        assertEquals(64, first.checksum().length());

        WriteChunkResponse retry = service.write(req);
        assertTrue(retry.deduplicated());
        assertEquals(first.checksum(), retry.checksum());
        assertEquals(first.storedSize(), retry.storedSize());

        // 同 ID 改版本 → 409
        ChunkConflictException e1 = assertThrowsConflict(
                () -> service.write(new WriteChunkRequest(id, 2, "src-A", Compression.NONE, points)));
        assertTrue(e1.getMessage().contains(id));

        // 同 ID 改来源 → 409
        assertThrowsConflict(() -> service.write(
                new WriteChunkRequest(id, 1, "src-B", Compression.NONE, points)));

        // 同 ID 改内容（点顺序变化即规范化内容变化）→ 409
        List<PointDatum> reordered = List.of(points.get(1), points.get(0));
        assertThrowsConflict(() -> service.write(
                new WriteChunkRequest(id, 1, "src-A", Compression.NONE, reordered)));
    }

    @Test
    void httpStatusesAre201Then200Then409() {
        String url = "http://localhost:" + port + "/api/lidar/chunks";
        String id = "http-1";
        WriteChunkRequest req = new WriteChunkRequest(id, 1, "web", Compression.NONE,
                List.of(new PointDatum(0, 0, 0, 1)));

        assertEquals(HttpStatus.CREATED, rest.postForEntity(url, req, String.class).getStatusCode());
        assertEquals(HttpStatus.OK, rest.postForEntity(url, req, String.class).getStatusCode());

        WriteChunkRequest conflict = new WriteChunkRequest(id, 2, "web", Compression.NONE,
                List.of(new PointDatum(0, 0, 0, 1)));
        assertEquals(HttpStatus.CONFLICT,
                rest.postForEntity(url, conflict, String.class).getStatusCode());
    }

    // ------------------------------------------------------------------
    // 查询：三类形状边界 + 时间闭区间 + 稳定排序
    // ------------------------------------------------------------------

    @Test
    void queriesIncludeBoundariesAndSortStably() {
        // 块 A：盒边界上的点
        service.write(new WriteChunkRequest("q-A", 3, "src-A", Compression.NONE, List.of(
                new PointDatum(0, 0, 0, 100),
                new PointDatum(10, 10, 10, 300),
                new PointDatum(5, 5, 5, 200),
                new PointDatum(11, 5, 5, 400))));
        // 块 B：与 A 有相同时间戳，用于验证 chunkId 次序
        service.write(new WriteChunkRequest("q-B", 1, "src-B", Compression.NONE, List.of(
                new PointDatum(5, 5, 5, 200),
                new PointDatum(5, 5, 5, 200))));

        SpatialQueryResponse box = service.query(new SpatialQueryRequest(
                QueryType.BOX, new BBox(0, 0, 0, 10, 10, 10),
                null, null, null, null, null, null));
        // 边界点全包含：(0,0,0)/(10,10,10)/(5,5,5)x3；(11,5,5) 排除
        assertEquals(5, box.points().size());
        // 稳定排序：t=100 → t=200 的 A 块点(index=2) 先于 B 块两点(index0,1) → t=300
        assertEquals(100, box.points().get(0).t());
        assertEquals("q-A", box.points().get(1).chunkId());
        assertEquals(2, box.points().get(1).pointIndex());
        assertEquals("q-B", box.points().get(2).chunkId());
        assertEquals(0, box.points().get(2).pointIndex());
        assertEquals("q-B", box.points().get(3).chunkId());
        assertEquals(1, box.points().get(3).pointIndex());
        assertEquals(300, box.points().get(4).t());
        // 返回实际读取块的版本与来源
        assertEquals(3, box.points().get(0).version());
        assertEquals("src-A", box.points().get(0).source());
        assertEquals(1, box.points().get(2).version());
        assertEquals("src-B", box.points().get(2).source());

        // 时间闭区间 [200,300]：排除 t=100 与 t=400，留 4 个
        SpatialQueryResponse ranged = service.query(new SpatialQueryRequest(
                QueryType.BOX, new BBox(-100, -100, -100, 100, 100, 100),
                null, null, null, null, 200L, 300L));
        assertEquals(4, ranged.points().size());
        assertEquals(200, ranged.points().get(0).t());
        assertEquals(300, ranged.points().get(ranged.points().size() - 1).t());

        // RADIUS：球心 (0,0,0) 半径 5，球面点 (5,0,0)/(0,-5,0) 必含，(0,0,5.0001) 排除
        service.write(new WriteChunkRequest("q-R", 1, "src-R", Compression.NONE, List.of(
                new PointDatum(5, 0, 0, 1),
                new PointDatum(0, -5, 0, 2),
                new PointDatum(0, 0, 5.0001, 3))));
        SpatialQueryResponse radius = service.query(new SpatialQueryRequest(
                QueryType.RADIUS, null, new Vec3(0, 0, 0), 5.0,
                null, null, null, null));
        Set<String> radiusHits = new HashSet<>();
        radius.points().forEach(p -> radiusHits.add(p.chunkId() + "@" + p.pointIndex()));
        assertTrue(radiusHits.contains("q-R@0"));
        assertTrue(radiusHits.contains("q-R@1"));
        assertFalse(radiusHits.contains("q-R@2"));

        // CORRIDOR：沿 X 轴折线宽 2，柱面点 (3,1,0) 与端帽点 (-1,0,0) 必含，(3,1.0001,0) 排除
        service.write(new WriteChunkRequest("q-C", 1, "src-C", Compression.NONE, List.of(
                new PointDatum(3, 1, 0, 1),
                new PointDatum(-1, 0, 0, 2),
                new PointDatum(3, 1.0001, 0, 3))));
        SpatialQueryResponse corridor = service.query(new SpatialQueryRequest(
                QueryType.CORRIDOR, null, null, null,
                List.of(new Vec3(0, 0, 0), new Vec3(10, 0, 0)), 2.0, null, null));
        Set<String> corridorHits = new HashSet<>();
        corridor.points().forEach(p -> corridorHits.add(p.chunkId() + "@" + p.pointIndex()));
        assertTrue(corridorHits.contains("q-C@0"));
        assertTrue(corridorHits.contains("q-C@1"));
        assertFalse(corridorHits.contains("q-C@2"));
    }

    // ------------------------------------------------------------------
    // 损坏隔离：查询排除 + 隔离目录 + 审计 + 索引移除
    // ------------------------------------------------------------------

    @Test
    void corruptedChunkIsQuarantinedExcludedAndAudited() throws IOException {
        String id = "bad-1";
        List<PointDatum> points = List.of(
                new PointDatum(0, 0, 0, 1), new PointDatum(1, 1, 1, 2));
        service.write(new WriteChunkRequest(id, 1, "src-X", Compression.NONE, points));

        // 直接破坏块文件载荷（翻转文件尾附近一个字节）
        Path file = fileStore.getChunksDir().resolve(ChunkFileStore.encodeFileName(id));
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 8] ^= 0x01;
        Files.write(file, bytes);

        long auditsBefore = audit.countByType(PointCloudService.EVENT_QUARANTINED);
        SpatialQueryResponse resp = service.query(new SpatialQueryRequest(
                QueryType.BOX, new BBox(-100, -100, -100, 100, 100, 100),
                null, null, null, null, null, null));

        assertTrue(resp.quarantinedChunks().contains(id));
        assertTrue(resp.points().stream().noneMatch(p -> id.equals(p.chunkId())));
        assertEquals(auditsBefore + 1, audit.countByType(PointCloudService.EVENT_QUARANTINED));
        assertTrue(index.findById(id).isEmpty());
        assertTrue(Files.notExists(file));
        try (var quarantined = Files.list(fileStore.getQuarantineDir())) {
            String prefix = ChunkFileStore.encodeFileName(id).replace(".lpc", "");
            assertTrue(quarantined.anyMatch(p -> p.getFileName().toString().startsWith(prefix)));
        }

        // 再次查询不再出现该块；同 ID 可重新写入完好数据
        SpatialQueryResponse second = service.query(new SpatialQueryRequest(
                QueryType.BOX, new BBox(-100, -100, -100, 100, 100, 100),
                null, null, null, null, null, null));
        assertFalse(second.quarantinedChunks().contains(id));

        WriteChunkResponse rewritten = service.write(
                new WriteChunkRequest(id, 1, "src-X", Compression.NONE, points));
        assertFalse(rewritten.deduplicated());
    }

    // ------------------------------------------------------------------
    // 并发写入
    // ------------------------------------------------------------------

    @Test
    void concurrentWritesDistinctIdsAllPersist() throws Exception {
        int threads = 8;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    String cid = "cc-" + tid + "-" + i;
                    service.write(new WriteChunkRequest(cid, 1, "src", Compression.NONE,
                            List.of(new PointDatum(tid, i, 0, 100L + i))));
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        SpatialQueryResponse all = service.query(new SpatialQueryRequest(
                QueryType.BOX, new BBox(-1, -1, -1, 1000, 1000, 1000),
                null, null, null, null, null, null));
        long hits = all.points().stream().filter(p -> p.chunkId().startsWith("cc-")).count();
        assertEquals(threads * perThread, hits);
    }

    @Test
    void concurrentSameIdSameContentIsIdempotent() throws Exception {
        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<WriteChunkResponse>> futures = new ArrayList<>();
        List<PointDatum> points = List.of(new PointDatum(1, 2, 3, 99));
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return service.write(new WriteChunkRequest(
                        "same-id", 1, "dup-src", Compression.NONE, points));
            }));
        }
        start.countDown();
        int created = 0;
        int dedup = 0;
        for (Future<WriteChunkResponse> f : futures) {
            WriteChunkResponse r = f.get(30, TimeUnit.SECONDS);
            if (r.deduplicated()) {
                dedup++;
            } else {
                created++;
            }
        }
        pool.shutdownNow();
        assertEquals(1, created, "同 ID 同内容只允许落一份块");
        assertEquals(n - 1, dedup);
        try (var files = Files.list(fileStore.getChunksDir())) {
            assertEquals(1, files.filter(f -> f.getFileName().toString().contains("same-id")).count());
        }
    }

    @Test
    void concurrentSameIdDifferentContentHasOneWinnerRestConflict() throws Exception {
        int n = 5;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int v = i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    service.write(new WriteChunkRequest("race-id", v, "s", Compression.NONE,
                            List.of(new PointDatum(v, 0, 0, 1))));
                    return 201;
                } catch (ChunkConflictException e) {
                    return 409;
                }
            }));
        }
        start.countDown();
        int ok = 0;
        int conflict = 0;
        for (Future<Integer> f : futures) {
            if (f.get(30, TimeUnit.SECONDS) == 201) {
                ok++;
            } else {
                conflict++;
            }
        }
        pool.shutdownNow();
        assertEquals(1, ok);
        assertEquals(n - 1, conflict);
    }

    // ------------------------------------------------------------------
    // 重建：清空索引后从文件重建；重建窗口内的增量块立即可查且完成后不遗漏
    // ------------------------------------------------------------------

    @Test
    void rebuildRestoresIndexAndKeepsIncrementalWrites() throws Exception {
        int base = 40;
        List<PointDatum> repetitive = Collections.nCopies(500,
                new PointDatum(7.5, 7.5, 7.5, 500L));
        for (int i = 0; i < base; i++) {
            service.write(new WriteChunkRequest("rb-" + i, 1, "rb-src",
                    Compression.GZIP, repetitive));
        }
        // 模拟索引损坏/丢失
        jdbc.update("DELETE FROM lidar_chunk");
        assertEquals(0, index.count());

        rebuildSnapshotTaken = new CountDownLatch(1);
        incrementalWritesDone = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<RebuildResponse> rebuildFuture = pool.submit(service::rebuild);

        assertTrue(rebuildSnapshotTaken.await(10, TimeUnit.SECONDS));
        // 文件快照之后写入新块：重建不得遗漏这些增量
        int incremental = 5;
        for (int i = 0; i < incremental; i++) {
            String cid = "rb-new-" + i;
            service.write(new WriteChunkRequest(cid, 9, "live-src", Compression.NONE,
                    List.of(new PointDatum(7.5, 7.5, 7.5, 600 + i))));
            // 重建期间新块必须立即可查询
            SpatialQueryResponse immediate = service.query(new SpatialQueryRequest(
                    QueryType.BOX, new BBox(0, 0, 0, 10, 10, 10),
                    null, null, null, null, null, null));
            assertTrue(immediate.points().stream().anyMatch(p -> cid.equals(p.chunkId())),
                    "重建期间写入的块应立即可查: " + cid);
        }
        incrementalWritesDone.countDown();

        RebuildResponse result = rebuildFuture.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();
        rebuildSnapshotTaken = null;
        incrementalWritesDone = null;

        assertEquals(base, result.chunksIndexed(), "存量块应全部从文件重建入索引");
        assertEquals(base + incremental, result.totalChunks(),
                "重建完成后不得遗漏重建期间增量写入的块");

        SpatialQueryResponse after = service.query(new SpatialQueryRequest(
                QueryType.BOX, new BBox(0, 0, 0, 10, 10, 10),
                null, null, null, null, null, null));
        Set<String> chunkIds = new HashSet<>();
        after.points().forEach(p -> chunkIds.add(p.chunkId()));
        for (int i = 0; i < base; i++) {
            assertTrue(chunkIds.contains("rb-" + i), "重建块缺失: rb-" + i);
        }
        for (int i = 0; i < incremental; i++) {
            assertTrue(chunkIds.contains("rb-new-" + i), "增量块缺失: rb-new-" + i);
        }

        // 再做一次幂等重建不应产生重复插入
        RebuildResponse second = service.rebuild();
        assertEquals(0, second.chunksIndexed());
        assertEquals(base + incremental, second.totalChunks());
    }

    private interface ThrowingRunnable {
        void run();
    }

    private static ChunkConflictException assertThrowsConflict(ThrowingRunnable r) {
        try {
            r.run();
        } catch (ChunkConflictException e) {
            return e;
        }
        throw new AssertionError("期望 ChunkConflictException");
    }
}
