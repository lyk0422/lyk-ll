package com.example.starter.slam.integration;

import com.example.starter.slam.api.EdgeRequest;
import com.example.starter.slam.api.EdgeView;
import com.example.starter.slam.api.KeyFrameRequest;
import com.example.starter.slam.api.KeyFrameView;
import com.example.starter.slam.domain.EdgeStatus;
import com.example.starter.slam.domain.EdgeType;
import com.example.starter.slam.service.GraphService;
import com.example.starter.slam.store.GraphStore;
import com.example.starter.slam.testsupport.SlamTestConfiguration;
import com.example.starter.slam.web.ConflictException;
import com.example.starter.slam.web.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图写入链路的 H2(MODE=MySQL) 集成测试：幂等、同键异参 409、引用完整性、
 * 回环阈值校验、REJECTED 审计不进图不增版本、失败事务回滚。
 */
@SpringBootTest(
        classes = {com.example.starter.StarterApplication.class, SlamTestConfiguration.class},
        properties = {
                "spring.datasource.url=jdbc:h2:mem:slam_test_graph_write;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "slam.queue.enabled=false"
        })
class GraphWriteIntegrationTest {

    private static final List<Double> IDENTITY_INFORMATION =
            List.of(100.0, 0.0, 0.0, 0.0, 100.0, 0.0, 0.0, 0.0, 100.0);

    @Autowired
    private GraphService graphService;

    @Autowired
    private GraphStore graphStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM edge");
        jdbcTemplate.update("DELETE FROM key_frame");
        jdbcTemplate.update("DELETE FROM pose_graph");
    }

    private KeyFrameRequest frame(String id, double y) {
        return new KeyFrameRequest(id, "odom", 1, List.of(Double.parseDouble(id.substring(1)), y, 0.0));
    }

    @Test
    void duplicateFrameWithSameContentReturnsOriginalAndDoesNotIncrementVersion() {
        KeyFrameView first = graphService.putFrame("g1", "f1", frame("f1", 0.0));
        assertFalse(first.duplicated());
        assertEquals(1L, graphStore.currentVersion("g1"));

        KeyFrameView again = graphService.putFrame("g1", "f1", frame("f1", 0.0));
        assertTrue(again.duplicated());
        // H2 TIMESTAMP(6) 为微秒精度（按微秒舍入），证明返回的是原记录而非新建
        assertEquals(first.createdAt().getEpochSecond(), again.createdAt().getEpochSecond());
        assertTrue(Math.abs(first.createdAt().getNano() - again.createdAt().getNano()) <= 1000,
                "duplicate write must return the original timestamp, got "
                        + first.createdAt() + " vs " + again.createdAt());
        assertEquals(first.source(), again.source());
        assertEquals(1L, graphStore.currentVersion("g1"));
        assertEquals(1, frameCount());
    }

    @Test
    void sameFrameIdWithDifferentContentReturns409() {
        graphService.putFrame("g1", "f1", frame("f1", 0.0));
        assertThrows(ConflictException.class,
                () -> graphService.putFrame("g1", "f1", frame("f1", 0.5)));
    }

    @Test
    void duplicateEdgeWithSameContentReturnsOriginal() {
        seedTwoFrames();
        EdgeRequest edge = odometryEdge("e1", "req-1", "f1", "f2");
        EdgeView first = graphService.putEdge("g1", "e1", edge);
        EdgeView again = graphService.putEdge("g1", "e1", edge);
        assertEquals(EdgeStatus.VALID, first.status());
        assertFalse(first.duplicated());
        assertTrue(again.duplicated());
        // 两个关键帧各 +1，一条有效边 +1，重复写入不再递增
        assertEquals(3L, graphStore.currentVersion("g1"));
    }

    @Test
    void sameEdgeIdDifferentRequestIdReturns409() {
        seedTwoFrames();
        graphService.putEdge("g1", "e1", odometryEdge("e1", "req-1", "f1", "f2"));
        EdgeRequest changed = new EdgeRequest("e1", "req-OTHER", "odom", 1, EdgeType.ODOMETRY,
                "f1", "f2", List.of(1.0, 0.0, 0.0), IDENTITY_INFORMATION, null, null);
        assertThrows(ConflictException.class, () -> graphService.putEdge("g1", "e1", changed));
    }

    @Test
    void sameRequestIdDifferentContentReturns409() {
        seedTwoFrames();
        graphService.putEdge("g1", "e1", odometryEdge("e1", "req-1", "f1", "f2"));
        EdgeRequest changed = new EdgeRequest("e9", "req-1", "odom", 1, EdgeType.ODOMETRY,
                "f1", "f2", List.of(2.0, 0.0, 0.0), IDENTITY_INFORMATION, null, null);
        assertThrows(ConflictException.class, () -> graphService.putEdge("g1", "e9", changed));
    }

    @Test
    void edgeReferencingMissingFrameReturns404() {
        EdgeRequest edge = odometryEdge("e1", "req-1", "fX", "fY");
        assertThrows(NotFoundException.class, () -> graphService.putEdge("g1", "e1", edge));
    }

    @Test
    void untrustedLoopClosureIsPersistedRejectedButExcludedFromGraphAndVersion() {
        seedTwoFrames();
        long beforeLoop = graphStore.currentVersion("g1");

        EdgeRequest badLoop = new EdgeRequest("loop-bad", "req-loop-bad", "loop-detector", 1,
                EdgeType.LOOP, "f1", "f2", List.of(1.0, 0.0, 0.0), IDENTITY_INFORMATION,
                0.2, 0.1);
        EdgeView view = graphService.putEdge("g1", "loop-bad", badLoop);

        assertEquals(EdgeStatus.REJECTED, view.status());
        assertTrue(view.rejectReason().contains("score"));
        assertEquals(beforeLoop, view.graphVersion());
        assertEquals(beforeLoop, graphStore.currentVersion("g1"));
        // 审计记录存在，但不进入有效边集合
        assertEquals(EdgeStatus.REJECTED, graphStore.findEdgeById("loop-bad").status());
        assertTrue(graphStore.loadValidEdges("g1").isEmpty());
        assertEquals(0, graphStore.countValidEdges("g1"));
    }

    @Test
    void trustedLoopClosureEntersGraphAndIncrementsVersion() {
        seedTwoFrames();
        long beforeLoop = graphStore.currentVersion("g1");

        EdgeRequest goodLoop = new EdgeRequest("loop-good", "req-loop-good", "loop-detector", 1,
                EdgeType.LOOP, "f1", "f2", List.of(1.0, 0.0, 0.0), IDENTITY_INFORMATION,
                0.9, 0.1);
        EdgeView view = graphService.putEdge("g1", "loop-good", goodLoop);

        assertEquals(EdgeStatus.VALID, view.status());
        assertNull(view.rejectReason());
        assertEquals(beforeLoop + 1, view.graphVersion());
        assertEquals(1, graphStore.countValidEdges("g1"));
    }

    @Test
    void strictVersionedThresholdRejectsLoopAtVersionTwo() {
        seedTwoFrames();
        // 0.8 分在 v1 基线(0.75)可过，在 v2 严格阈值(0.85)下必须拒绝
        EdgeView view = graphService.putEdge("g1", "v2-loop", new EdgeRequest("v2-loop", "req-v2",
                "loop-detector", 2, EdgeType.LOOP, "f1", "f2",
                List.of(1.0, 0.0, 0.0), IDENTITY_INFORMATION, 0.8, 0.1));
        assertEquals(EdgeStatus.REJECTED, view.status());
    }

    @Test
    void invalidRequestRollsBackAndLeavesNoEdge() {
        seedTwoFrames();
        EdgeRequest invalid = new EdgeRequest("e-bad", "req-bad", "odom", 1, EdgeType.ODOMETRY,
                "f1", "f2", List.of(Double.NaN, 0.0, 0.0), IDENTITY_INFORMATION, null, null);
        assertThrows(IllegalArgumentException.class, () -> graphService.putEdge("g1", "e-bad", invalid));
        assertNull(graphStore.findEdgeById("e-bad"));
    }

    private void seedTwoFrames() {
        graphService.putFrame("g1", "f1", frame("f1", 0.0));
        graphService.putFrame("g1", "f2", frame("f2", 0.0));
    }

    private EdgeRequest odometryEdge(String id, String requestId, String from, String to) {
        return new EdgeRequest(id, requestId, "odom", 1, EdgeType.ODOMETRY,
                from, to, List.of(1.0, 0.0, 0.0), IDENTITY_INFORMATION, null, null);
    }

    private int frameCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM key_frame", Integer.class);
        return count == null ? 0 : count;
    }
}
