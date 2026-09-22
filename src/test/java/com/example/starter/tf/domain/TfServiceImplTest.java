package com.example.starter.tf.domain;

import com.example.starter.tf.TfTestConfiguration;
import com.example.starter.tf.math.Transform3d;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TfServiceImpl} 的领域集成测试，跑在真实嵌入式 H2（MODE=MySQL）上，
 * 缓存窗口固定为 1000 纳秒。覆盖环检测、多跳组合、插值边界、乱序补录、
 * 幂等/409 冲突、缓存淘汰与各类失败分支，以及并发读写的一致性。
 */
@SpringBootTest(classes = {com.example.starter.StarterApplication.class, TfTestConfiguration.class})
class TfServiceImplTest {

    private static final double EPS = 1.0e-9;

    @Autowired
    private TfService tfService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM tf_dynamic_sample");
        jdbcTemplate.update("DELETE FROM tf_static_transform");
        jdbcTemplate.update("DELETE FROM tf_edge");
        jdbcTemplate.update("DELETE FROM tf_frame");
    }

    private static Transform3d translation(double x, double y, double z) {
        return new Transform3d(x, y, z, 0, 0, 0, 1);
    }

    private static Transform3d rotZ(double angle) {
        return new Transform3d(0, 0, 0, 0, 0, Math.sin(angle / 2), Math.cos(angle / 2));
    }

    // ---------- 基础与多跳组合 ----------

    @Test
    void registerFrameIsIdempotent() {
        tfService.registerFrame("world");
        tfService.registerFrame("world");
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tf_frame WHERE frame_name = 'world'", Integer.class);
        assertEquals(1, count);
    }

    @Test
    void multiHopStaticChainComposesUpAndDown() {
        tfService.writeStatic("world", "a", "s", translation(1, 0, 0));
        tfService.writeStatic("a", "b", "s", translation(0, 2, 0));

        LookupResult bInWorld = tfService.lookup("b", "world", 123L);
        assertEquals(List.of("b", "a", "world"), bInWorld.path());
        assertEquals(1.0, bInWorld.transform().tx(), EPS);
        assertEquals(2.0, bInWorld.transform().ty(), EPS);
        assertFalse(bInWorld.interpolated());

        LookupResult worldInB = tfService.lookup("world", "b", 123L);
        assertEquals(List.of("world", "a", "b"), worldInB.path());
        assertEquals(-1.0, worldInB.transform().tx(), EPS);
        assertEquals(-2.0, worldInB.transform().ty(), EPS);
    }

    @Test
    void pathThroughCommonAncestorComposesUpThenInverseDown() {
        // r -> a -> x 与 r -> a -> y，查询 x 到 y（LCA = a）
        tfService.writeStatic("r", "a", "s", translation(10, 0, 0));
        tfService.writeStatic("a", "x", "s", translation(1, 0, 0));
        tfService.writeStatic("a", "y", "s", translation(0, 1, 0));

        // x 原点在 a 中为 (1,0)，在 y 坐标系中：y 原点在 a 为 (0,1)，姿态均为单位
        LookupResult result = tfService.lookup("x", "y", 1L);
        assertEquals(List.of("x", "a", "y"), result.path());
        assertEquals(1.0, result.transform().tx(), EPS);
        assertEquals(-1.0, result.transform().ty(), EPS);
    }

    @Test
    void sameFrameLookupIsIdentity() {
        tfService.registerFrame("solo");
        LookupResult result = tfService.lookup("solo", "solo", 999L);
        assertEquals(List.of("solo"), result.path());
        assertEquals(0.0, result.transform().tx(), EPS);
        assertEquals(1.0, result.transform().qw(), EPS);
    }

    // ---------- 环检测 ----------

    @Test
    void addingEdgeThatClosesLoopIsRejectedWithFullCandidateCycle() {
        tfService.writeStatic("world", "a", "s", translation(0, 0, 0));
        tfService.writeStatic("a", "b", "s", translation(0, 0, 0));

        TfException.CycleDetected ex = assertThrows(TfException.CycleDetected.class,
                () -> tfService.writeStatic("b", "world", "s", translation(0, 0, 0)));
        assertEquals(List.of("world", "a", "b", "world"), ex.cycle());
        // 被拒绝的边不得落库
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tf_edge WHERE parent_frame = 'b' AND child_frame = 'world'",
                Integer.class));
    }

    @Test
    void selfLoopIsRejected() {
        tfService.registerFrame("x");
        assertThrows(TfException.CycleDetected.class,
                () -> tfService.writeStatic("x", "x", "s", translation(0, 0, 0)));
    }

    // ---------- 动态插值与边界 ----------

    @Test
    void dynamicEdgeInterpolatesBetweenAdjacentSamplesAndHitsSamplesExactly() {
        tfService.writeStatic("world", "base", "s", translation(100, 0, 0));
        tfService.writeDynamic("base", "sensor", "odom", 100, translation(0, 0, 0));
        tfService.writeDynamic("base", "sensor", "odom", 200, translation(10, 0, 0));

        LookupResult atSample = tfService.lookup("sensor", "world", 100L);
        assertEquals(100.0, atSample.transform().tx(), EPS);
        assertFalse(atSample.interpolated());

        LookupResult mid = tfService.lookup("sensor", "world", 150L);
        assertEquals(105.0, mid.transform().tx(), EPS, "中点平移线性插值");
        assertTrue(mid.interpolated());

        LookupResult quarter = tfService.lookup("sensor", "world", 125L);
        assertEquals(102.5, quarter.transform().tx(), EPS);
        assertTrue(quarter.interpolated());
    }

    @Test
    void dynamicRotationIsSphericallyInterpolated() {
        tfService.writeDynamic("world", "q", "odom", 0, rotZ(0.0));
        tfService.writeDynamic("world", "q", "odom", 100, rotZ(Math.PI / 2));

        LookupResult mid = tfService.lookup("q", "world", 50L);
        // 中点姿态应是绕 Z 轴 45°
        assertEquals(Math.sin(Math.PI / 8), mid.transform().qz(), EPS);
        assertEquals(Math.cos(Math.PI / 8), mid.transform().qw(), EPS);
        assertTrue(mid.interpolated());
    }

    @Test
    void mixedStaticAndDynamicChainInterpolatesAcrossInvertedEdge() {
        // world <-base(静态平移 100,0,0)- base <-gripper(动态，沿 X 从 0 走到 10)
        tfService.writeStatic("world", "base", "calib", translation(100, 0, 0));
        tfService.writeDynamic("base", "gripper", "odom", 0, translation(0, 0, 0));
        tfService.writeDynamic("base", "gripper", "odom", 100, translation(10, 0, 0));

        // world -> gripper：先沿 base 静态边取逆（-100），再沿动态边取逆，中点动态平移为 5
        // gripper 原点在 world 为 (105,0)；world 原点在 gripper 系为 (-105,0)
        LookupResult worldToGripper = tfService.lookup("world", "gripper", 50L);
        assertEquals(List.of("world", "base", "gripper"), worldToGripper.path());
        assertEquals(-105.0, worldToGripper.transform().tx(), EPS);
        assertTrue(worldToGripper.interpolated());

        LookupResult gripperToWorld = tfService.lookup("gripper", "world", 50L);
        assertEquals(105.0, gripperToWorld.transform().tx(), EPS);
        // 正反查询互为逆
        assertEquals(0.0,
                gripperToWorld.transform().multiply(worldToGripper.transform()).tx(), EPS);
    }

    @Test
    void outOfOrderSamplesAreAcceptedAndOrderedForInterpolation() {
        tfService.writeDynamic("world", "mov", "late", 200, translation(10, 0, 0));
        tfService.writeDynamic("world", "mov", "late", 100, translation(0, 0, 0));

        LookupResult mid = tfService.lookup("mov", "world", 150L);
        assertEquals(5.0, mid.transform().tx(), EPS);
        assertTrue(mid.interpolated());

        assertThrows(TfException.BeforeEarliestSample.class,
                () -> tfService.lookup("mov", "world", 90L));
        assertThrows(TfException.AfterLatestSample.class,
                () -> tfService.lookup("mov", "world", 201L));
    }

    @Test
    void beforeEarliestAndAfterLatestProduceDistinctErrors() {
        tfService.writeDynamic("world", "d", "odom", 1000, translation(0, 0, 0));
        tfService.writeDynamic("world", "d", "odom", 2000, translation(1, 0, 0));

        assertThrows(TfException.BeforeEarliestSample.class,
                () -> tfService.lookup("d", "world", 999L));
        assertThrows(TfException.AfterLatestSample.class,
                () -> tfService.lookup("d", "world", 2001L));
    }

    // ---------- 幂等与 409 ----------

    @Test
    void sameDynamicReplayIsIdempotentAndDoesNotResample() {
        tfService.writeDynamic("world", "e", "src", 500, translation(1, 0, 0));
        tfService.writeDynamic("world", "e", "src", 500, translation(1, 0, 0));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tf_dynamic_sample WHERE edge_id = "
                        + "(SELECT id FROM tf_edge WHERE child_frame = 'e')", Integer.class);
        assertEquals(1, count);
    }

    @Test
    void differentContentAtSameKeyConflictsWith409() {
        tfService.writeDynamic("world", "e", "src", 500, translation(1, 0, 0));
        TfException.ReplayConflict ex = assertThrows(TfException.ReplayConflict.class,
                () -> tfService.writeDynamic("world", "e", "src", 500, translation(2, 0, 0)));
        assertEquals(409, ex.httpStatus());

        // 换来源但时间戳相同同样冲突
        assertThrows(TfException.ReplayConflict.class,
                () -> tfService.writeDynamic("world", "e", "other", 500, translation(1, 0, 0)));
    }

    @Test
    void staticReplayIdempotentButDifferentPoseConflicts() {
        tfService.writeStatic("world", "fixed", "s", translation(1, 0, 0));
        tfService.writeStatic("world", "fixed", "s", translation(1, 0, 0));
        assertThrows(TfException.ReplayConflict.class,
                () -> tfService.writeStatic("world", "fixed", "s", translation(2, 0, 0)));
    }

    @Test
    void changingEdgeTypeOrParentConflicts() {
        tfService.writeStatic("world", "k", "s", translation(0, 0, 0));
        assertThrows(TfException.ReplayConflict.class,
                () -> tfService.writeDynamic("world", "k", "s", 1, translation(0, 0, 0)));

        tfService.writeStatic("world", "m", "s", translation(0, 0, 0));
        tfService.registerFrame("other");
        // m 的唯一入边父节点已是 world，换父节点必须冲突
        assertThrows(TfException.ReplayConflict.class,
                () -> tfService.writeStatic("other", "m", "s", translation(0, 0, 0)));
    }

    // ---------- 缓存淘汰 ----------

    @Test
    void evictionRemovesOnlyWindowSlackAndReportsDistinctGoneError() throws Exception {
        // 窗口 1000ns：写入 12000 后，cutoff=11000，10000、10500 被淘汰
        tfService.writeDynamic("world", "h", "odom", 10000, translation(0, 0, 0));
        tfService.writeDynamic("world", "h", "odom", 10500, translation(5, 0, 0));
        tfService.writeDynamic("world", "h", "odom", 12000, translation(20, 0, 0));

        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tf_dynamic_sample WHERE edge_id = "
                        + "(SELECT id FROM tf_edge WHERE child_frame = 'h')", Integer.class);
        assertEquals(1, remaining, "窗口外样本必须被淘汰");

        // 历史时刻曾采样但已淘汰 -> 缓存窗口错误
        assertThrows(TfException.CacheWindowExceeded.class,
                () -> tfService.lookup("h", "world", 10500L));
        // 窗口内但样本也已被删（10500 < 11000 截止）同样是窗口错误
        assertThrows(TfException.CacheWindowExceeded.class,
                () -> tfService.lookup("h", "world", 11500L));
        // 早于历史最早采样 -> 另一类错误
        assertThrows(TfException.BeforeEarliestSample.class,
                () -> tfService.lookup("h", "world", 9000L));
        // 保留样本仍可精确命中
        LookupResult kept = tfService.lookup("h", "world", 12000L);
        assertEquals(20.0, kept.transform().tx(), EPS);
        assertFalse(kept.interpolated());

        // 新样本推进窗口，cutoff=12000，严格小于截止故 12000 保留，12500 为整齐中点
        tfService.writeDynamic("world", "h", "odom", 13000, translation(30, 0, 0));
        LookupResult stillKept = tfService.lookup("h", "world", 12000L);
        assertEquals(20.0, stillKept.transform().tx(), EPS);
        LookupResult interp = tfService.lookup("h", "world", 12500L);
        assertEquals(25.0, interp.transform().tx(), EPS);
        assertTrue(interp.interpolated());
    }

    @Test
    void manualEvictionDeletesExpiredSamplesAcrossEdges() {
        tfService.writeDynamic("world", "g1", "s", 100, translation(0, 0, 0));
        tfService.writeDynamic("world", "g2", "s", 100, translation(0, 0, 0));
        tfService.writeDynamic("world", "g1", "s", 5000, translation(1, 0, 0));
        tfService.writeDynamic("world", "g2", "s", 6000, translation(1, 0, 0));

        // 写入时已自动淘汰，紧跟一次手动淘汰无新增删除
        assertEquals(0, tfService.evictExpired());
        assertThrows(TfException.CacheWindowExceeded.class,
                () -> tfService.lookup("g1", "world", 100L));
        assertThrows(TfException.CacheWindowExceeded.class,
                () -> tfService.lookup("g2", "world", 100L));

        // 绕过服务直接补两条窗口外的乱序老样本：
        // g1 cutoff=4000、g2 cutoff=5000，3900 对两边都严格落在窗口外
        jdbcTemplate.update("INSERT INTO tf_dynamic_sample "
                        + "(edge_id, stamp_nanos, source, tx, ty, tz, qx, qy, qz, qw, created_at_nanos) "
                        + "SELECT id, 3900, 'backfill', 0, 0, 0, 0, 0, 0, 1, 0 FROM tf_edge WHERE child_frame = 'g1'");
        jdbcTemplate.update("INSERT INTO tf_dynamic_sample "
                        + "(edge_id, stamp_nanos, source, tx, ty, tz, qx, qy, qz, qw, created_at_nanos) "
                        + "SELECT id, 3900, 'backfill', 0, 0, 0, 0, 0, 0, 1, 0 FROM tf_edge WHERE child_frame = 'g2'");
        assertEquals(2, tfService.evictExpired());
        Integer outside = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tf_dynamic_sample s JOIN tf_edge e ON s.edge_id = e.id "
                        + "WHERE e.child_frame IN ('g1','g2') AND s.stamp_nanos = 3900", Integer.class);
        assertEquals(0, outside);
    }

    // ---------- 坐标系与链路失败分支 ----------

    @Test
    void missingFrameAndBrokenPathProduceDistinctErrors() {
        tfService.registerFrame("alive");
        assertThrows(TfException.FrameNotFound.class,
                () -> tfService.lookup("alive", "ghost", 1L));
        assertThrows(TfException.FrameNotFound.class,
                () -> tfService.lookup("ghost", "alive", 1L));

        tfService.registerFrame("tree2");
        assertThrows(TfException.NoTransformPath.class,
                () -> tfService.lookup("alive", "tree2", 1L));
    }

    // ---------- 并发 ----------

    @Test
    void concurrentDynamicWritesPersistEverySampleAndNeverCorruptReads() throws Exception {
        tfService.registerFrame("world");
        final int threads = 8;
        final int perThread = 50;
        final long base = 1_000_000L; // 全部样本跨度 399ns，小于 1000ns 窗口，不触发淘汰
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger unexpected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            futures.add(pool.submit(() -> {
                for (int j = 0; j < perThread; j++) {
                    long stamp = base + j * threads + threadId;
                    tfService.writeDynamic("world", "chan", "w" + threadId,
                            stamp, translation(stamp, 0, 0));
                }
            }));
        }

        // 并发读者：只允许出现题干定义的时间范围/帧类业务异常，不允许内部错误
        List<Callable<Void>> readers = new ArrayList<>();
        for (int r = 0; r < 4; r++) {
            readers.add(() -> {
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(800);
                while (System.nanoTime() < deadline) {
                    long stamp = base + (int) (Math.random() * (threads * perThread - 1));
                    try {
                        LookupResult result = tfService.lookup("chan", "world", stamp);
                        if (result.transform().tx() < base || result.transform().tx() > base + 400) {
                            unexpected.incrementAndGet();
                        }
                    } catch (TfException.BeforeEarliestSample | TfException.AfterLatestSample expected) {
                        // 样本尚未齐时合法
                    }
                }
                return null;
            });
        }
        for (Callable<Void> reader : readers) {
            futures.add(pool.submit(reader));
        }

        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(0, unexpected.get(), "并发查询出现越界结果");

        // 所有样本无丢失，且每个时刻精确返回写入位姿
        for (int i = 0; i < threads * perThread; i++) {
            LookupResult result = tfService.lookup("chan", "world", base + i);
            assertEquals((double) base + i, result.transform().tx(), EPS);
        }
    }

    @Test
    void concurrentEdgeWritesDoNotExposeHalfInsertedStateToReaders() throws Exception {
        tfService.registerFrame("world");
        final int threads = 8;
        final int edgesPerThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger internalErrors = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            futures.add(pool.submit(() -> {
                for (int i = 0; i < edgesPerThread; i++) {
                    String child = "c_" + threadId + "_" + i;
                    tfService.writeStatic("world", child, "s",
                            translation(threadId, i, 0));
                }
            }));
        }

        for (int r = 0; r < 4; r++) {
            final int readerId = r;
            futures.add(pool.submit(() -> {
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(800);
                while (System.nanoTime() < deadline) {
                    String child = "c_" + (readerId % threads) + "_"
                            + (int) (Math.random() * edgesPerThread);
                    try {
                        tfService.lookup(child, "world", 1L);
                    } catch (TfException.FrameNotFound expected) {
                        // 该边尚未提交，合法
                    } catch (RuntimeException internal) {
                        internalErrors.incrementAndGet();
                    }
                }
            }));
        }

        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(0, internalErrors.get(), "读者观察到了半成品边或内部错误");

        // 全部边最终可见且位姿完整
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < edgesPerThread; i++) {
                LookupResult result = tfService.lookup("c_" + t + "_" + i, "world", 1L);
                assertEquals(t, result.transform().tx(), EPS);
                assertEquals(i, result.transform().ty(), EPS);
            }
        }
    }
}
