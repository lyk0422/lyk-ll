package com.example.starter.slam;

import com.example.starter.slam.service.GraphWriteService;
import com.example.starter.slam.web.ConflictException;
import com.example.starter.slam.web.UnprocessableException;
import com.example.starter.slam.web.dto.EdgeRequest;
import com.example.starter.slam.web.dto.KeyframeRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 图写入主流程与失败分支：判重、同键异参 409、requestId 冲突、
 * 边引用校验、回环阈值拒绝且不递增图版本。
 */
class GraphWriteServiceTest extends AbstractSlamIntegrationTest {

    @Autowired
    private GraphWriteService graphWriteService;

    private static KeyframeRequest keyframe(String id, String requestId) {
        JsonNode measurement = JsonNodeFactory.instance.objectNode()
                .put("scanId", id)
                .put("points", 100);
        return new KeyframeRequest(id, "lidar-a", 0, 1.0, 2.0, 0.1, measurement, requestId);
    }

    private static EdgeRequest odometry(String id, String from, String to, String requestId) {
        return new EdgeRequest(id, "ODOMETRY", "lidar-a", 0, from, to,
                0.5, 0.0, 0.0, goodCovariance(), null, null, requestId);
    }

    private static EdgeRequest loop(String id, String from, String to, double score,
                                    double residual, String requestId) {
        return new EdgeRequest(id, "LOOP", "lidar-a", 0, from, to,
                0.5, 0.0, 0.0, goodCovariance(), score, residual, requestId);
    }

    @Test
    void writesKeyframesAndBumpsGraphVersion() {
        GraphWriteService.KeyframeWriteResult r1 = graphWriteService.writeKeyframe(keyframe("kf1", "req-kf1"));
        GraphWriteService.KeyframeWriteResult r2 = graphWriteService.writeKeyframe(keyframe("kf2", "req-kf2"));

        assertThat(r1.deduped()).isFalse();
        assertThat(r1.keyframe().graphVersion()).isEqualTo(1);
        assertThat(r2.keyframe().graphVersion()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM keyframes", Integer.class)).isEqualTo(2);
    }

    @Test
    void duplicateKeyframeSameContentReturnsOriginal() {
        graphWriteService.writeKeyframe(keyframe("kf1", "req-kf1"));
        // 同 ID 同内容重复写入（即使 requestId 不同）：返回原记录，标记 deduped，不再递增版本
        GraphWriteService.KeyframeWriteResult repeated = graphWriteService.writeKeyframe(keyframe("kf1", "req-kf1b"));

        assertThat(repeated.deduped()).isTrue();
        assertThat(repeated.keyframe().requestId()).isEqualTo("req-kf1");
        assertThat(jdbc.queryForObject("SELECT current_version FROM graph_meta WHERE id=1", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void sameKeyframeIdDifferentContentIsConflict() {
        graphWriteService.writeKeyframe(keyframe("kf1", "req-kf1"));
        JsonNode otherMeasurement = JsonNodeFactory.instance.objectNode().put("scanId", "different");
        KeyframeRequest changed = new KeyframeRequest("kf1", "lidar-a", 0, 9.0, 9.0, 0.9,
                otherMeasurement, "req-kf1-changed");

        assertThatThrownBy(() -> graphWriteService.writeKeyframe(changed))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exists with different content");
    }

    @Test
    void reusedRequestIdAcrossKeyframesIsConflict() {
        graphWriteService.writeKeyframe(keyframe("kf1", "shared-req"));
        assertThatThrownBy(() -> graphWriteService.writeKeyframe(keyframe("kf2", "shared-req")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already used by keyframe kf1");
    }

    @Test
    void duplicateEdgeSameContentReturnsOriginal() {
        graphWriteService.writeKeyframe(keyframe("kf1", "req-kf1"));
        graphWriteService.writeKeyframe(keyframe("kf2", "req-kf2"));
        GraphWriteService.EdgeWriteResult first =
                graphWriteService.writeEdge(odometry("e1", "kf1", "kf2", "req-e1"));
        GraphWriteService.EdgeWriteResult repeated =
                graphWriteService.writeEdge(odometry("e1", "kf1", "kf2", "req-e1b"));

        assertThat(first.deduped()).isFalse();
        assertThat(first.edge().graphVersion()).isEqualTo(3);
        assertThat(repeated.deduped()).isTrue();
        assertThat(repeated.edge().requestId()).isEqualTo("req-e1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edges", Integer.class)).isEqualTo(1);
        // 重复写入不再次递增图版本
        assertThat(jdbc.queryForObject("SELECT current_version FROM graph_meta WHERE id=1", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void sameEdgeIdDifferentContentIsConflict() {
        graphWriteService.writeKeyframe(keyframe("kf1", "req-kf1"));
        graphWriteService.writeKeyframe(keyframe("kf2", "req-kf2"));
        graphWriteService.writeEdge(odometry("e1", "kf1", "kf2", "req-e1"));

        EdgeRequest changed = new EdgeRequest("e1", "ODOMETRY", "lidar-a", 0, "kf1", "kf2",
                0.9, 0.0, 0.0, goodCovariance(), null, null, "req-e1-changed");
        assertThatThrownBy(() -> graphWriteService.writeEdge(changed))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exists with different content");
    }

    @Test
    void edgeReferencingUnknownKeyframeIsRejected() {
        graphWriteService.writeKeyframe(keyframe("kf1", "req-kf1"));
        assertThatThrownBy(() -> graphWriteService.writeEdge(odometry("e1", "kf1", "ghost", "req-e1")))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("unknown keyframe");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edges", Integer.class)).isZero();
    }

    @Test
    void trustedLoopBecomesValidAndBumpsVersion() {
        graphWriteService.writeKeyframe(keyframe("kf1", "req-kf1"));
        graphWriteService.writeKeyframe(keyframe("kf2", "req-kf2"));
        GraphWriteService.EdgeWriteResult result =
                graphWriteService.writeEdge(loop("loop1", "kf1", "kf2", 0.95, 0.1, "req-loop1"));

        assertThat(result.edge().status().name()).isEqualTo("VALID");
        assertThat(result.edge().rejectReason()).isNull();
        assertThat(result.edge().graphVersion()).isEqualTo(3);
    }

    @Test
    void conflictingLoopIsAuditedButDoesNotEnterGraphOrBumpVersion() {
        graphWriteService.writeKeyframe(keyframe("kf1", "req-kf1"));
        graphWriteService.writeKeyframe(keyframe("kf2", "req-kf2"));
        int versionBefore = jdbc.queryForObject(
                "SELECT current_version FROM graph_meta WHERE id=1", Integer.class);

        // 分数过低：score 0.5 < 0.75
        GraphWriteService.EdgeWriteResult rejected =
                graphWriteService.writeEdge(loop("loop-bad", "kf1", "kf2", 0.5, 0.1, "req-loop-bad"));

        assertThat(rejected.edge().status().name()).isEqualTo("REJECTED");
        assertThat(rejected.edge().rejectReason()).contains("score");
        assertThat(rejected.edge().graphVersion()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT current_version FROM graph_meta WHERE id=1", Integer.class)).isEqualTo(versionBefore);
        Integer validEdges = jdbc.queryForObject(
                "SELECT COUNT(*) FROM edges WHERE status='VALID'", Integer.class);
        assertThat(validEdges).isZero();
        Integer audited = jdbc.queryForObject(
                "SELECT COUNT(*) FROM edges WHERE status='REJECTED'", Integer.class);
        assertThat(audited).isEqualTo(1);
    }

    @Test
    void loopWithoutScoreIsUnprocessable() {
        graphWriteService.writeKeyframe(keyframe("kf1", "req-kf1"));
        graphWriteService.writeKeyframe(keyframe("kf2", "req-kf2"));
        EdgeRequest noScore = new EdgeRequest("loop-x", "LOOP", "lidar-a", 0, "kf1", "kf2",
                0.5, 0.0, 0.0, goodCovariance(), null, null, "req-loop-x");
        assertThatThrownBy(() -> graphWriteService.writeEdge(noScore))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("score");
    }

    @Test
    void reusedRequestIdAcrossKeyframeAndEdgeIsConflict() {
        graphWriteService.writeKeyframe(keyframe("kf1", "shared"));
        graphWriteService.writeKeyframe(keyframe("kf2", "req-kf2"));
        assertThatThrownBy(() -> graphWriteService.writeEdge(odometry("e1", "kf1", "kf2", "shared")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already used by keyframe");
    }

    @Test
    void concurrentSameEdgeWritesYieldExactlyOneRow() throws Exception {
        // H2 唯一约束 + 行锁边界：并发写入同 ID 同内容的边，恰好一次新建，其余判重返回原记录
        graphWriteService.writeKeyframe(keyframe("kf1", "req-kf1"));
        graphWriteService.writeKeyframe(keyframe("kf2", "req-kf2"));

        int threads = 6;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger created = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger deduped = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        GraphWriteService.EdgeWriteResult r =
                                graphWriteService.writeEdge(odometry("e-race", "kf1", "kf2", "req-race"));
                        if (r.deduped()) {
                            deduped.incrementAndGet();
                        } else {
                            created.incrementAndGet();
                        }
                    } catch (Throwable t) {
                        error.compareAndSet(null, t);
                    }
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> f : futures) {
                f.get(15, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(error.get()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM edges WHERE id='e-race'", Integer.class))
                .isEqualTo(1);
        assertThat(created.get()).isEqualTo(1);
        assertThat(deduped.get()).isEqualTo(threads - 1);
        // 重复写边不额外递增图版本：2 个关键帧 + 1 条边 = 3
        assertThat(jdbc.queryForObject("SELECT current_version FROM graph_meta WHERE id=1", Integer.class))
                .isEqualTo(3);
    }
}
