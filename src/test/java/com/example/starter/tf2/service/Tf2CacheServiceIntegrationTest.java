package com.example.starter.tf2.service;

import com.example.starter.tf2.domain.EdgeType;
import com.example.starter.tf2.persistence.TransformSample;
import com.example.starter.tf2.persistence.TransformSampleRepository;
import com.example.starter.tf2.web.TransformRequest;
import com.example.starter.tf2.web.TransformResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基于 H2（MODE=MySQL）的服务层集成测试，覆盖环检测、多跳组合、插值边界、
 * 缓存淘汰、乱序补录、同参幂等/异参 409、各类查询错误、唯一约束与事务回滚、
 * 以及并发读写快照一致性。
 */
@SpringBootTest
@ActiveProfiles("test")
class Tf2CacheServiceIntegrationTest {

    private static final double EPS = 1e-9;

    @Autowired
    private Tf2CacheService service;

    @Autowired
    private TransformSampleRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM tf_transform_sample");
    }

    // ------------------------------------------------------------------
    // 环检测
    // ------------------------------------------------------------------

    @Test
    void selfLoopRejectedWithCompleteCycle() {
        service.publish(dynamic("A", "B", 1L, 0, 0, 0, "s1"));

        Tf2Exception.CycleDetected ex = assertThrows(Tf2Exception.CycleDetected.class,
                () -> service.publish(dynamic("A", "A", 1L, 0, 0, 0, "s2")));
        assertEquals(List.of("A", "A"), ex.getCycle());
    }

    @Test
    void multiHopCycleRejectedWithCompleteCandidatePath() {
        // A->B->C 合法森林；再插 C->A 成环
        service.publish(dynamic("A", "B", 1L, 0, 0, 0, "s1"));
        service.publish(dynamic("B", "C", 1L, 0, 0, 0, "s2"));

        Tf2Exception.CycleDetected ex = assertThrows(Tf2Exception.CycleDetected.class,
                () -> service.publish(dynamic("C", "A", 1L, 0, 0, 0, "s3")));
        assertEquals(List.of("A", "B", "C", "A"), ex.getCycle());
        // 被拒绝的边未落库：C 仍是根之外的叶子，A 仍无父
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tf_transform_sample", Integer.class));
    }

    @Test
    void childFrameCannotHaveTwoParents() {
        service.publish(dynamic("A", "B", 1L, 0, 0, 0, "s1"));
        assertThrows(Tf2Exception.FrameParentConflict.class,
                () -> service.publish(dynamic("C", "B", 1L, 0, 0, 0, "s2")));
    }

    // ------------------------------------------------------------------
    // 多跳组合
    // ------------------------------------------------------------------

    @Test
    void composesMultiHopStaticChain() {
        // world->base 平移 (1,0,0)；base->tool 平移 (0,2,0)，均静态
        service.publish(staticEdge("world", "base", 1, 0, 0, "s1"));
        service.publish(staticEdge("base", "tool", 0, 2, 0, "s2"));

        TransformResponse forward = service.lookup("world", "tool", 0L);
        assertEquals(List.of("world", "base", "tool"), forward.path());
        assertEquals(1.0, forward.tx(), EPS);
        assertEquals(2.0, forward.ty(), EPS);

        // 反向查询取逆
        TransformResponse backward = service.lookup("tool", "world", 0L);
        assertEquals(List.of("tool", "base", "world"), backward.path());
        assertEquals(-1.0, backward.tx(), EPS);
        assertEquals(-2.0, backward.ty(), EPS);
    }

    @Test
    void composesStaticAndDynamicHopsWithRotation() {
        // world->base 静态：绕 Z 轴 90°，平移 (10,0,0)
        service.publish(staticEdge("world", "base", 10, 0, 0,
                0, 0, Math.sin(Math.PI / 4), Math.cos(Math.PI / 4), "s1"));
        // base->arm 动态：t=1000 时平移 (1,0,0)；t=2000 时平移 (3,0,0)
        service.publish(dynamic("base", "arm", 1000L, 1, 0, 0, "s2"));
        service.publish(dynamic("base", "arm", 2000L, 3, 0, 0, "s2"));

        // t=1500 插值平移 (2,0,0)，再经 world->base：Rz90*(2,0,0)=(0,2,0)，总平移 (10,2,0)
        TransformResponse r = service.lookup("world", "arm", 1500L);
        assertEquals(10.0, r.tx(), 1e-8);
        assertEquals(2.0, r.ty(), 1e-8);
        assertEquals(0.0, r.tz(), EPS);
        // 旋转为 Rz90 * identity = Rz90
        assertEquals(Math.cos(Math.PI / 4), r.qw(), 1e-8);
        assertEquals(Math.sin(Math.PI / 4), r.qz(), 1e-8);
    }

    // ------------------------------------------------------------------
    // 插值边界
    // ------------------------------------------------------------------

    @Test
    void interpolationAtSamplesAndMidpoint() {
        service.publish(dynamic("A", "B", 100L, 0, 0, 0, "s1"));
        service.publish(dynamic("A", "B", 200L, 10, 0, 0, "s1"));

        assertEquals(0.0, service.lookup("A", "B", 100L).tx(), EPS);
        assertEquals(10.0, service.lookup("A", "B", 200L).tx(), EPS);
        assertEquals(5.0, service.lookup("A", "B", 150L).tx(), EPS);
    }

    @Test
    void rejectsBeforeEarliestAndAfterLatest() {
        // 样本跨度 500ns（小于窗口 1000ns），查询 750 落在窗口内但早于最早样本
        service.publish(dynamic("A", "B", 1000L, 0, 0, 0, "s1"));
        service.publish(dynamic("A", "B", 1500L, 10, 0, 0, "s1"));

        assertThrows(Tf2Exception.TimeBeforeEarliest.class,
                () -> service.lookup("A", "B", 750L));
        assertThrows(Tf2Exception.TimeAfterLatest.class,
                () -> service.lookup("A", "B", 1501L));
    }

    // ------------------------------------------------------------------
    // 乱序补录
    // ------------------------------------------------------------------

    @Test
    void acceptsOutOfOrderSamplesAndQueriesCorrectly() {
        // 先写末尾与中间，再补最早样本；跨度恰为窗口大小，任何样本都不会被淘汰
        service.publish(dynamic("A", "B", 2000L, 20, 0, 0, "s1"));
        service.publish(dynamic("A", "B", 1000L, 10, 0, 0, "s1"));
        service.publish(dynamic("A", "B", 1800L, 18, 0, 0, "s1"));

        assertEquals(10.0, service.lookup("A", "B", 1000L).tx(), EPS);
        assertEquals(15.0, service.lookup("A", "B", 1500L).tx(), EPS);
        assertEquals(20.0, service.lookup("A", "B", 2000L).tx(), EPS);
        assertEquals(3, repository.findDynamicSamples("A", "B").size());
    }

    // ------------------------------------------------------------------
    // 缓存淘汰
    // ------------------------------------------------------------------

    @Test
    void evictsOnlySamplesOutsideWindow() {
        // window=1000ns：stamp 0 在写入 5500 时即被淘汰（0 < 4500）
        service.publish(dynamic("A", "B", 0L, 0, 0, 0, "s1"));
        service.publish(dynamic("A", "B", 5500L, 55, 0, 0, "s1"));
        service.publish(dynamic("A", "B", 6000L, 60, 0, 0, "s1"));

        List<TransformSample> remaining = repository.findDynamicSamples("A", "B");
        assertEquals(2, remaining.size());
        assertEquals(5500L, remaining.get(0).stampNanos());
        assertEquals(6000L, remaining.get(1).stampNanos());

        // 窗口起点 = 6000-1000 = 5000；4000 已在窗口外
        assertThrows(Tf2Exception.OutsideCacheWindow.class,
                () -> service.lookup("A", "B", 4000L));
        // 5200 在窗口内但早于现存最早样本 5500
        assertThrows(Tf2Exception.TimeBeforeEarliest.class,
                () -> service.lookup("A", "B", 5200L));
        // 窗口内样本仍可插值
        assertEquals(57.5, service.lookup("A", "B", 5750L).tx(), EPS);
    }

    // ------------------------------------------------------------------
    // 幂等与冲突
    // ------------------------------------------------------------------

    @Test
    void identicalReplayIsIdempotent() {
        service.publish(dynamic("A", "B", 1000L, 1, 2, 3, "src"));
        assertDoesNotThrow(() -> service.publish(dynamic("A", "B", 1000L, 1, 2, 3, "src")));
        assertEquals(1, repository.findDynamicSamples("A", "B").size());

        service.publish(staticEdge("A", "C", 7, 8, 9, "src"));
        assertDoesNotThrow(() -> service.publish(staticEdge("A", "C", 7, 8, 9, "src")));
        assertEquals(1, repository.findStatic("A", "C").size());
    }

    @Test
    void sameKeyDifferentContentReturns409AndKeepsOriginal() {
        service.publish(dynamic("A", "B", 1000L, 1, 0, 0, "src"));
        assertThrows(Tf2Exception.ConflictingReplay.class,
                () -> service.publish(dynamic("A", "B", 1000L, 9, 0, 0, "src")));

        // 不同来源占用同一时刻且内容不同同样 409
        assertThrows(Tf2Exception.ConflictingReplay.class,
                () -> service.publish(dynamic("A", "B", 1000L, 9, 0, 0, "other")));
        // 原始样本保持不变（事务内未产生脏写）
        TransformSample kept = repository.findDynamicSamples("A", "B").get(0);
        assertEquals(1.0, kept.translation().x(), EPS);
        assertEquals("src", kept.source());
    }

    @Test
    void staticEdgeChangeReturns409() {
        service.publish(staticEdge("A", "B", 1, 0, 0, "s1"));
        assertThrows(Tf2Exception.ConflictingReplay.class,
                () -> service.publish(staticEdge("A", "B", 2, 0, 0, "s1")));
    }

    @Test
    void mixingStaticAndDynamicOnSameEdgeRejected() {
        service.publish(staticEdge("A", "B", 1, 0, 0, "s1"));
        assertThrows(Tf2Exception.ConflictingReplay.class,
                () -> service.publish(dynamic("A", "B", 1000L, 1, 0, 0, "s2")));
    }

    // ------------------------------------------------------------------
    // 查询错误区分
    // ------------------------------------------------------------------

    @Test
    void missingFramesAndDisconnectedChainHaveDistinctErrors() {
        service.publish(dynamic("A", "B", 1000L, 0, 0, 0, "s1"));
        service.publish(dynamic("C", "D", 1000L, 0, 0, 0, "s2"));

        assertThrows(Tf2Exception.FrameNotFound.class,
                () -> service.lookup("A", "GHOST", 1000L));
        assertThrows(Tf2Exception.FrameNotFound.class,
                () -> service.lookup("GHOST", "A", 1000L));
        assertThrows(Tf2Exception.ChainDisconnected.class,
                () -> service.lookup("A", "D", 1000L));
    }

    @Test
    void invalidRequestsRejectedWith400() {
        assertThrows(Tf2Exception.InvalidRequest.class,
                () -> service.publish(dynamic("A", "B", null, 0, 0, 0, "s1")));
        assertThrows(Tf2Exception.InvalidRequest.class,
                () -> service.publish(new TransformRequest(
                        "A", "B", true, 1L, 0, 0, 0, 0, 0, 0, 0.0, "s1")));
    }

    // ------------------------------------------------------------------
    // H2 唯一约束（真实数据库边界，不使用 mock）
    // ------------------------------------------------------------------

    @Test
    void uniqueConstraintEnforcedByH2() {
        TransformSample row = new TransformSample(
                null, "A", "B", EdgeType.DYNAMIC, 1000L,
                new com.example.starter.tf2.domain.Vec3(0, 0, 0),
                com.example.starter.tf2.domain.Quat.IDENTITY, "src", Instant.now());
        repository.insert(row);
        assertThrows(DuplicateKeyException.class, () -> repository.insert(row));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tf_transform_sample", Integer.class));
    }

    // ------------------------------------------------------------------
    // 并发读写：查询快照一致性与同参并发幂等
    // ------------------------------------------------------------------

    @Test
    void concurrentWritersDoNotCorruptReads() throws Exception {
        // 预置 1000 时刻样本，读线程全程查询该时刻
        service.publish(dynamic("A", "B", 1000L, 1, 0, 0, "init"));

        int writerThreads = 4;
        int readerThreads = 4;
        int writesPerThread = 250;
        ExecutorService pool = Executors.newFixedThreadPool(writerThreads + readerThreads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int w = 0; w < writerThreads; w++) {
            final int base = w;
            futures.add(pool.submit(() -> {
                assertUnchecked(start);
                for (int i = 0; i < writesPerThread; i++) {
                    // 4 线程共覆盖 1000 个不同 stamp（1001..2000）；乱序到达时互不淘汰
                    long stamp = 1001L + (base * writesPerThread + i) % 1000L;
                    try {
                        // 不同 stamp 互不冲突；同一 stamp 内容相同，重放幂等
                        service.publish(dynamic("A", "B", stamp, stamp, 0, 0, "writer"));
                    } catch (Tf2Exception.ConflictingReplay e) {
                        failures.incrementAndGet();
                    }
                }
            }));
        }
        for (int r = 0; r < readerThreads; r++) {
            futures.add(pool.submit(() -> {
                assertUnchecked(start);
                for (int i = 0; i < 500; i++) {
                    try {
                        TransformResponse resp = service.lookup("A", "B", 1000L);
                        // 已开始的历史查询必须始终观察到 1000 时刻的稳定样本
                        if (Math.abs(resp.tx() - 1.0) > EPS) {
                            failures.incrementAndGet();
                        }
                    } catch (RuntimeException e) {
                        failures.incrementAndGet();
                    }
                }
            }));
        }

        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(0, failures.get(), "并发读写出现冲突或快照不一致");

        // 最终样本数 = 初始 1000 行 + 1001..2000 共 1000 个不同 stamp（幂等无重复）
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tf_transform_sample WHERE edge_type='DYNAMIC'", Integer.class);
        assertEquals(1001, count);
    }

    private static void assertUnchecked(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------
    // 测试请求构造
    // ------------------------------------------------------------------

    private static TransformRequest staticEdge(String parent, String child,
                                               double tx, double ty, double tz, String source) {
        return new TransformRequest(parent, child, false, null,
                tx, ty, tz, 0, 0, 0, 1.0, source);
    }

    private static TransformRequest staticEdge(String parent, String child,
                                               double tx, double ty, double tz,
                                               double qx, double qy, double qz, double qw,
                                               String source) {
        return new TransformRequest(parent, child, false, null,
                tx, ty, tz, qx, qy, qz, qw, source);
    }

    private static TransformRequest dynamic(String parent, String child, Long stamp,
                                            double tx, double ty, double tz, String source) {
        return new TransformRequest(parent, child, true, stamp,
                tx, ty, tz, 0, 0, 0, 1.0, source);
    }
}
