package com.example.starter.tf.persistence;

import com.example.starter.tf.domain.EdgeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基于真实嵌入式 H2（MODE=MySQL）的仓库测试：
 * 验证唯一约束、乱序样本按时间戳读取、淘汰删除与外键等数据库边界。
 */
@SpringBootTest
class JdbcTfRepositoryTest {

    @Autowired
    private TfRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM tf_dynamic_sample");
        jdbcTemplate.update("DELETE FROM tf_static_transform");
        jdbcTemplate.update("DELETE FROM tf_edge");
        jdbcTemplate.update("DELETE FROM tf_frame");
    }

    private SampleRow sample(long stamp, double tx) {
        return new SampleRow(stamp, "src", tx, 0, 0, 0, 0, 0, 1);
    }

    @Test
    void frameInsertIsIdempotentViaPrimaryKey() {
        assertTrue(repository.insertFrameIfAbsent("world", 1));
        assertThrows(DuplicateKeyException.class,
                () -> repository.insertFrameIfAbsent("world", 2));
        assertTrue(repository.frameExists("world"));
    }

    @Test
    void childFrameUniqueEnforcesSingleIncomingEdge() {
        repository.insertFrameIfAbsent("a", 1);
        repository.insertFrameIfAbsent("b", 1);
        repository.insertFrameIfAbsent("c", 1);
        repository.insertEdge("a", "b", EdgeType.STATIC, "s", 1);
        assertThrows(DuplicateKeyException.class,
                () -> repository.insertEdge("c", "b", EdgeType.STATIC, "s", 1));
    }

    @Test
    void edgeForeignKeyRejectsUnknownFrame() {
        assertThrows(Exception.class,
                () -> repository.insertEdge("ghost_parent", "ghost_child", EdgeType.STATIC, "s", 1));
    }

    @Test
    void dynamicSamplesReadInTimestampOrderDespiteOutOfOrderInsert() {
        repository.insertFrameIfAbsent("a", 1);
        repository.insertFrameIfAbsent("b", 1);
        long edgeId = repository.insertEdge("a", "b", EdgeType.DYNAMIC, "s", 1);

        // 乱序插入：先 200，再 100
        repository.insertDynamicSample(edgeId, sample(200, 2), 2);
        repository.insertDynamicSample(edgeId, sample(100, 1), 3);

        assertEquals(100, repository.findSampleAtOrAfter(edgeId, 0).orElseThrow().stampNanos());
        assertEquals(200, repository.findLastSample(edgeId).orElseThrow().stampNanos());

        Optional<SampleRow> before = repository.findSampleAtOrBefore(edgeId, 150);
        Optional<SampleRow> after = repository.findSampleAtOrAfter(edgeId, 150);
        assertEquals(100, before.orElseThrow().stampNanos());
        assertEquals(200, after.orElseThrow().stampNanos());
    }

    @Test
    void duplicateTimestampViolatesUniqueKeyRegardlessOfSource() {
        repository.insertFrameIfAbsent("a", 1);
        repository.insertFrameIfAbsent("b", 1);
        long edgeId = repository.insertEdge("a", "b", EdgeType.DYNAMIC, "s", 1);
        repository.insertDynamicSample(edgeId, sample(100, 1), 1);

        SampleRow otherSource = new SampleRow(100, "other", 9, 0, 0, 0, 0, 0, 1);
        assertThrows(DuplicateKeyException.class,
                () -> repository.insertDynamicSample(edgeId, otherSource, 2));
    }

    @Test
    void evictionDeletesOnlySamplesBeforeCutoff() {
        repository.insertFrameIfAbsent("a", 1);
        repository.insertFrameIfAbsent("b", 1);
        long edgeId = repository.insertEdge("a", "b", EdgeType.DYNAMIC, "s", 1);
        repository.insertDynamicSample(edgeId, sample(100, 1), 1);
        repository.insertDynamicSample(edgeId, sample(200, 2), 1);
        repository.insertDynamicSample(edgeId, sample(300, 3), 1);

        Optional<EvictionOutcome> outcome = repository.deleteSamplesOlderThan(edgeId, 250);
        assertTrue(outcome.isPresent());
        assertEquals(2, outcome.get().deletedCount());
        assertEquals(200, outcome.get().maxStampNanos());

        List<Long> remaining = jdbcTemplate.queryForList(
                "SELECT stamp_nanos FROM tf_dynamic_sample WHERE edge_id = ? ORDER BY stamp_nanos",
                Long.class, edgeId);
        assertEquals(List.of(300L), remaining);

        assertFalse(repository.deleteSamplesOlderThan(edgeId, 250).isPresent());
    }

    @Test
    void evictionWatermarkAdvancesMonotonically() {
        repository.insertFrameIfAbsent("a", 1);
        repository.insertFrameIfAbsent("b", 1);
        long edgeId = repository.insertEdge("a", "b", EdgeType.DYNAMIC, "s", 1);
        repository.updateEvictedUpto(edgeId, 100);
        repository.updateEvictedUpto(edgeId, 50);
        Long watermark = jdbcTemplate.queryForObject(
                "SELECT evicted_upto_nanos FROM tf_edge WHERE id = ?", Long.class, edgeId);
        assertEquals(100L, watermark);
    }

    @Test
    void firstSampleWatermarkMovesDownToEarliestOutOfOrderSample() {
        repository.insertFrameIfAbsent("a", 1);
        repository.insertFrameIfAbsent("b", 1);
        long edgeId = repository.insertEdge("a", "b", EdgeType.DYNAMIC, "s", 1);

        repository.touchFirstSample(edgeId, 200);
        repository.touchFirstSample(edgeId, 100);
        repository.touchFirstSample(edgeId, 150);

        EdgeRow edge = repository.findEdgeByChild("b").orElseThrow();
        assertEquals(100L, edge.firstSampleNanos());
    }

    @Test
    void staticPoseRoundTrips() {
        repository.insertFrameIfAbsent("a", 1);
        repository.insertFrameIfAbsent("b", 1);
        long edgeId = repository.insertEdge("a", "b", EdgeType.STATIC, "s", 1);
        repository.insertStaticPose(edgeId, "s", sample(0, 7), 1);

        SampleRow pose = repository.findStaticPose(edgeId).orElseThrow();
        assertEquals(7.0, pose.tx());
        assertThrows(DuplicateKeyException.class,
                () -> repository.insertStaticPose(edgeId, "s", sample(0, 8), 2));
    }
}
