package com.example.starter.perception.service;

import com.example.starter.perception.api.AssociationRequest;
import com.example.starter.perception.api.AssociationResponse;
import com.example.starter.perception.api.DetectionRequest;
import com.example.starter.perception.api.DetectionResult;
import com.example.starter.perception.api.TrackSnapshotResponse;
import com.example.starter.perception.config.TimeService;
import com.example.starter.perception.error.BusinessRuleException;
import com.example.starter.perception.error.VersionConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基于 H2（MODE=MySQL）内存库的端到端业务测试：真实建表、唯一约束、乐观锁、事务回滚与并发写入。
 */
@SpringBootTest
class TrackServiceH2Test {

    private static final Instant T0 = Instant.parse("2026-09-22T10:00:00Z");

    @Autowired
    private TrackService trackService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TimeService timeService;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM processed_detection");
        jdbcTemplate.update("DELETE FROM track_event");
        jdbcTemplate.update("DELETE FROM track");
        jdbcTemplate.update("ALTER SEQUENCE seq_track_no RESTART WITH 1");
        timeService.setClock(Clock.fixed(T0, ZoneOffset.UTC));
    }

    @AfterEach
    void resetClock() {
        timeService.resetClock();
    }

    // ---------- 主流程：建轨、关联、版本严格递增、事件落库 ----------

    @Test
    void firstDetectionCreatesTrackAndFollowingDetectionsTrackIt() {
        AssociationResponse response = associate(List.of(
                detection("d1", T0, 0, 0, 0.9),
                detection("d2", T0.plusMillis(200), 0.5, 0, 0.8)));

        assertEquals(2, response.acceptedCount());
        DetectionResult first = response.results().get(0);
        DetectionResult second = response.results().get(1);
        assertEquals("TRK-00000001", first.trackId());
        assertEquals("CREATED", first.state());
        assertEquals(1, first.version());
        assertEquals("TRK-00000001", second.trackId());
        assertEquals("TRACKING", second.state());
        assertEquals(2, second.version());

        TrackSnapshotResponse snapshot = trackService.currentSnapshot("TRK-00000001");
        assertEquals("TRACKING", snapshot.state());
        assertEquals(0.5, snapshot.x(), 1e-9);
        assertEquals(T0.plusMillis(200), snapshot.lastObservedAt());
        assertEquals(2, snapshot.version());

        assertEquals(2, count("track_event", "track_id = 'TRK-00000001'"));
    }

    // ---------- 低置信度拒绝：不产生任何轨迹变化与事件 ----------

    @Test
    void lowConfidenceDetectionIsRejectedWithoutAnyChange() {
        trackService.associate(new AssociationRequest(List.of(
                detection("d1", T0, 0, 0, 0.9)), null));
        long versionBefore = versionOf("TRK-00000001");

        AssociationResponse response = associate(List.of(
                detection("low1", T0.plusMillis(100), 0.1, 0, 0.49),
                detection("low2", T0.plusMillis(120), 0.1, 0, 0.50 - 1e-12)));

        assertEquals(0, response.acceptedCount());
        assertEquals(2, response.rejectedCount());
        assertEquals("LOW_CONFIDENCE", response.results().get(0).reason());
        assertEquals(versionBefore, versionOf("TRK-00000001"));
        // 拒绝的检测不写事件流。
        assertEquals(1, count("track_event", null));
        // 但首次处理结论落库，重复提交才能幂等。
        assertEquals(2, count("processed_detection", "accepted = FALSE"));
    }

    // ---------- 乱序拒绝：最近观测、版本、状态均不得回退 ----------

    @Test
    void outOfOrderDetectionMustNotRollbackTrack() {
        associate(List.of(
                detection("d1", T0, 0, 0, 0.9),
                detection("d2", T0.plusMillis(200), 0.5, 0, 0.8)));
        long versionBefore = versionOf("TRK-00000001");

        AssociationResponse response = associate(List.of(
                detection("d3-late", T0.plusMillis(100), 0.1, 0, 0.9)));

        DetectionResult result = response.results().get(0);
        assertFalse(result.accepted());
        assertEquals("OUT_OF_ORDER", result.reason());
        assertEquals(versionBefore, versionOf("TRK-00000001"));
        TrackSnapshotResponse snapshot = trackService.currentSnapshot("TRK-00000001");
        assertEquals(T0.plusMillis(200), snapshot.lastObservedAt());
        assertEquals("TRACKING", snapshot.state());
        assertEquals(0.5, snapshot.x(), 1e-9);
        assertEquals(2, count("track_event", "track_id = 'TRK-00000001'"));
    }

    // ---------- 重复 detectionId 幂等：返回首次结论，不重复建轨 ----------

    @Test
    void duplicateDetectionIdIsIdempotent() {
        DetectionRequest d1 = detection("dup-id", T0, 3, 3, 0.9);
        trackService.associate(new AssociationRequest(List.of(d1), null));
        long eventsBefore = count("track_event", null);

        AssociationResponse second = associate(List.of(d1));

        DetectionResult result = second.results().get(0);
        assertTrue(result.accepted());
        assertTrue(result.reason().startsWith("DUPLICATE"));
        assertEquals("TRK-00000001", result.trackId());
        assertEquals(1, result.version());
        assertEquals(1, trackService.currentSnapshots().size());
        assertEquals(eventsBefore, count("track_event", null));
    }

    @Test
    void duplicateRejectedDetectionIsIdempotentToo() {
        DetectionRequest low = detection("dup-low", T0, 3, 3, 0.1);
        AssociationResponse first = associate(List.of(low));
        AssociationResponse second = associate(List.of(low));
        assertFalse(first.results().get(0).accepted());
        assertFalse(second.results().get(0).accepted());
        assertEquals(0, trackService.currentSnapshots().size());
        assertEquals(1, count("processed_detection", "detection_id = 'dup-low'"));
    }

    // ---------- 短时遮挡：OCCLUDED 事件回填真实超时时刻，随后恢复 TRACKING ----------

    @Test
    void shortOcclusionMaterializesOccludedEventAndResumesTracking() {
        associate(List.of(
                detection("o1", T0, 0, 0, 0.9),
                detection("o2", T0.plusMillis(200), 0.2, 0, 0.8),
                detection("o3", T0.plusMillis(800), 0.4, 0, 0.85)));

        TrackSnapshotResponse current = trackService.currentSnapshot("TRK-00000001");
        assertEquals("TRACKING", current.state());
        // v1 CREATED, v2 TRACKING, v3 OCCLUDED(回填 T0+700), v4 重新观测 TRACKING
        assertEquals(4, current.version());

        TrackSnapshotResponse atGap = trackService.replaySnapshot(
                "TRK-00000001", T0.plusMillis(750));
        assertEquals("OCCLUDED", atGap.state());
        assertEquals(3, atGap.version());

        TrackSnapshotResponse beforeGap = trackService.replaySnapshot(
                "TRK-00000001", T0.plusMillis(700 - 1));
        assertEquals("TRACKING", beforeGap.state());
        assertEquals(2, beforeGap.version());
    }

    // ---------- 长时遮挡：LOST 后重识别，保留原轨迹ID并留证据 ----------

    @Test
    void longOcclusionLeadsToLostAndReidentificationKeepsOriginalTrackId() {
        associate(List.of(
                detection("l1", T0, 0, 0, 0.9),
                detection("l2", T0.plusMillis(200), 0.2, 0, 0.8)));
        associate(List.of(
                detection("l3", T0.plusMillis(3000), 0.5, 0, 0.87)));

        TrackSnapshotResponse reidentified = trackService.currentSnapshot("TRK-00000001");
        assertEquals("REIDENTIFIED", reidentified.state());
        // v1,v2 观测；v3 OCCLUDED@700；v4 LOST@2200；v5 REIDENTIFIED
        assertEquals(5, reidentified.version());
        assertEquals("REIDENTIFY", reidentified.evidence().get("decision"));
        assertEquals("PRESERVE_ORIGINAL_TRACK_ID", reidentified.evidence().get("idSwitchPolicy"));

        assertEquals("LOST", trackService.replaySnapshot(
                "TRK-00000001", T0.plusMillis(2500)).state());

        // 重识别确认后的后续观测：REIDENTIFIED -> TRACKING
        associate(List.of(detection("l4", T0.plusMillis(3200), 0.6, 0, 0.88)));
        assertEquals("TRACKING", trackService.currentSnapshot("TRK-00000001").state());
        assertEquals(6, trackService.currentSnapshot("TRK-00000001").version());
    }

    // ---------- 目标交叉：最近邻关联保持身份，证据列出全部候选 ----------

    @Test
    void crossingTargetsKeepDistinctIdentitiesWithExplainableEvidence() {
        // 两车在平行车道（y 相差2米）上相向行驶，第四帧时 x 次序发生交换（完成交叉），
        // 二维最近邻仍各自命中本车，且每条证据都列出另一候选及距离/门限。
        associate(List.of(
                detection("a0", T0, 0, 1, 0.9, "vehicle"),
                detection("b0", T0, 10, -1, 0.9, "vehicle")));
        associate(List.of(
                detection("a1", T0.plusMillis(200), 1.9, 1, 0.9, "vehicle"),
                detection("b1", T0.plusMillis(200), 8.1, -1, 0.9, "vehicle")));
        associate(List.of(
                detection("a2", T0.plusMillis(400), 3.8, 1, 0.9, "vehicle"),
                detection("b2", T0.plusMillis(400), 6.2, -1, 0.9, "vehicle")));
        AssociationResponse crossing = associate(List.of(
                detection("a3", T0.plusMillis(600), 5.7, 1, 0.9, "vehicle"),
                detection("b3", T0.plusMillis(600), 4.3, -1, 0.9, "vehicle")));

        Map<String, String> detectionToTrack = new HashMap<>();
        for (DetectionResult r : crossing.results()) {
            detectionToTrack.put(r.detectionId(), r.trackId());
        }
        assertEquals("TRK-00000001", detectionToTrack.get("a3"));
        assertEquals("TRK-00000002", detectionToTrack.get("b3"));
        assertEquals(2, trackService.currentSnapshots().size());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates =
                (List<Map<String, Object>>) crossing.results().get(0).evidence().get("candidates");
        assertEquals(2, candidates.size());
        assertTrue(candidates.stream().allMatch(c -> c.containsKey("distanceMeter")
                && c.containsKey("gateMeter") && c.containsKey("withinGate")));
        // 交叉帧上另一车道候选在门限之外，证据中可解释为何弃用。
        assertTrue(candidates.stream().anyMatch(c -> "TRK-00000002".equals(c.get("trackId"))
                && Boolean.FALSE.equals(c.get("withinGate"))));
    }

    @Test
    void exactEquidistantTieIsResolvedDeterministicallyByTrackId() {
        associate(List.of(
                detection("e0a", T0, 0, 0, 0.9, "vehicle"),
                detection("e0b", T0, 4, 0, 0.9, "vehicle")));
        AssociationResponse response = associate(List.of(
                detection("e1", T0.plusMillis(200), 2, 0, 0.9, "vehicle")));
        // 到两条轨迹距离都恰好等于门限2米，候选按 track_id 升序，TRK-00000001 稳定胜出。
        assertEquals("TRK-00000001", response.results().get(0).trackId());
    }

    // ---------- 不同类别不关联，远离门限另建新轨 ----------

    @Test
    void differentCategoryAndFarDetectionsCreateNewTracks() {
        associate(List.of(detection("c1", T0, 0, 0, 0.9, "vehicle")));
        AssociationResponse response = associate(List.of(
                detection("c2", T0.plusMillis(100), 0.1, 0, 0.9, "pedestrian"),
                detection("c3", T0.plusMillis(200), 50, 0, 0.9, "vehicle")));
        assertEquals("TRK-00000002", response.results().get(0).trackId());
        assertEquals("TRK-00000003", response.results().get(1).trackId());
    }

    // ---------- 乐观锁：期望版本不符整批 409，数据库无任何变化 ----------

    @Test
    void staleExpectedVersionRejectsWholeBatchAtomically() {
        associate(List.of(detection("v1", T0, 0, 0, 0.9)));
        long tracksBefore = count("track", null);
        long processedBefore = count("processed_detection", null);

        AssociationRequest stale = new AssociationRequest(
                List.of(detection("v2", T0.plusMillis(100), 0.2, 0, 0.9)),
                Map.of("TRK-00000001", 99L));
        assertThrows(VersionConflictException.class, () -> trackService.associate(stale));

        assertEquals(tracksBefore, count("track", null));
        assertEquals(processedBefore, count("processed_detection", null));
        assertEquals(1, versionOf("TRK-00000001"));
    }

    // ---------- 并发：同一轨迹的两个并发版本化请求，恰好一个成功，无丢失更新 ----------

    @Test
    void concurrentAssociationsOnSameTrackApplyExactlyOneUpdate() throws Exception {
        associate(List.of(detection("cc0", T0, 0, 0, 0.9)));

        int threads = 2;
        ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String id = "cc" + (i + 1);
            final Instant timestamp = T0.plusMillis(200 + i);
            final double x = 0.2 + i * 0.1;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                trackService.associate(new AssociationRequest(
                        List.of(detection(id, timestamp, x, 0, 0.9)),
                        Map.of("TRK-00000001", 1L)));
                return null;
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();

        int success = 0;
        int conflict = 0;
        for (Future<?> future : futures) {
            try {
                future.get(30, TimeUnit.SECONDS);
                success++;
            } catch (Exception e) {
                if (rootCauseIs(e, VersionConflictException.class)) {
                    conflict++;
                } else {
                    throw e;
                }
            }
        }
        pool.shutdownNow();

        assertEquals(1, success);
        assertEquals(1, conflict);
        // 版本只被推进一次，没有两个更新都落库。
        assertEquals(2, versionOf("TRK-00000001"));
    }

    // ---------- 回收：LOST -> RECYCLED，终态拒绝观测，非法迁移 422 ----------

    @Test
    void recycledTrackIsTerminalAndIllegalRecycleRejected() {
        associate(List.of(detection("r1", T0, 0, 0, 0.9)));
        // TRACKING 状态直接回收非法。
        assertThrows(BusinessRuleException.class,
                () -> trackService.recycle("TRK-00000001", null));

        // 批末补账把没有后续观测的轨迹推进到 LOST（500+1500ms）。
        associate(List.of(detection("r2-other", T0.plusMillis(3000), 20, 0, 0.9)));
        assertEquals("LOST", trackService.currentSnapshot("TRK-00000001").state());

        long versionBefore = versionOf("TRK-00000001");
        TrackSnapshotResponse recycled = trackService.recycle("TRK-00000001", null);
        assertEquals("RECYCLED", recycled.state());
        assertEquals(versionBefore + 1, recycled.version());

        // 回收后邻近新检测不得再关联它，而是另建新轨。
        AssociationResponse response = associate(List.of(
                detection("r3", T0.plusMillis(3100), 0.1, 0, 0.9)));
        assertEquals("TRK-00000003", response.results().get(0).trackId());

        assertThrows(BusinessRuleException.class,
                () -> trackService.recycle("TRK-00000001", null));
    }

    @Test
    void recycleWithStaleExpectedVersionRaisesConflict() {
        associate(List.of(detection("rs1", T0, 0, 0, 0.9)));
        associate(List.of(detection("rs2", T0.plusMillis(3000), 20, 0, 0.9)));
        long version = versionOf("TRK-00000001");
        assertThrows(VersionConflictException.class,
                () -> trackService.recycle("TRK-00000001", version - 1));
    }

    // ---------- 事件流重放：任意时刻快照、与当前态一致、确定性 ----------

    @Test
    void replayAtArbitraryInstantsRebuildsCorrectSnapshots() {
        associate(List.of(
                detection("p1", T0, 0, 0, 0.9),
                detection("p2", T0.plusMillis(200), 1, 0, 0.8)));
        associate(List.of(detection("p3", T0.plusMillis(400), 2, 0, 0.7)));

        TrackSnapshotResponse atT0 = trackService.replaySnapshot("TRK-00000001", T0);
        assertEquals("CREATED", atT0.state());
        assertEquals(1, atT0.version());
        assertEquals(0, atT0.x(), 1e-9);

        TrackSnapshotResponse at200 = trackService.replaySnapshot(
                "TRK-00000001", T0.plusMillis(200));
        assertEquals("TRACKING", at200.state());
        assertEquals(2, at200.version());
        assertEquals(1, at200.x(), 1e-9);

        TrackSnapshotResponse at400 = trackService.replaySnapshot(
                "TRK-00000001", T0.plusMillis(400));
        assertEquals(3, at400.version());
        assertEquals(2, at400.x(), 1e-9);
        assertEquals("REPLAYED", at400.source());
    }

    @Test
    void replayOfSameDetectionSetIsDeterministicAcrossFreshDatabases() throws Exception {
        List<Instant> checkpoints = List.of(T0, T0.plusMillis(300), T0.plusMillis(800),
                T0.plusMillis(2300), T0.plusMillis(3100));
        String firstRun = runScriptAndCapture(checkpoints);
        String secondRun = runScriptAndCapture(checkpoints);
        assertEquals(firstRun, secondRun);
    }

    private String runScriptAndCapture(List<Instant> checkpoints) throws Exception {
        jdbcTemplate.update("DELETE FROM processed_detection");
        jdbcTemplate.update("DELETE FROM track_event");
        jdbcTemplate.update("DELETE FROM track");
        jdbcTemplate.update("ALTER SEQUENCE seq_track_no RESTART WITH 1");

        associate(List.of(
                detection("z1", T0, 0, 0, 0.9, "vehicle"),
                detection("z2", T0, 10, 0, 0.9, "vehicle")));
        associate(List.of(
                detection("z3", T0.plusMillis(200), 1, 0, 0.85, "vehicle"),
                detection("z4", T0.plusMillis(200), 9, 0, 0.85, "vehicle")));
        // 乱序 + 低置信度 + 重复，结果也必须确定。
        associate(List.of(detection("z5-late", T0.plusMillis(100), 0.1, 0, 0.9, "vehicle")));
        associate(List.of(detection("z6-low", T0.plusMillis(300), 1.2, 0, 0.2, "vehicle")));
        associate(List.of(detection("z3", T0.plusMillis(200), 1, 0, 0.85, "vehicle")));
        associate(List.of(detection("z7", T0.plusMillis(3000), 1.2, 0, 0.88, "vehicle")));

        List<Object> captured = new ArrayList<>();
        for (Instant checkpoint : checkpoints) {
            List<TrackSnapshotResponse> snapshots = trackService.replaySnapshots(checkpoint);
            captured.add(snapshots.stream().map(s -> new ArrayList<>(List.of(
                    s.trackId(), s.state(), s.version(), s.x(), s.y(),
                    s.confidence(), s.lastObservedAt(), s.category()))).toList());
        }
        return objectMapper.writeValueAsString(captured);
    }

    // ---------- 当前态派生展示：尚未补账的超时状态可按当前时钟读出 ----------

    @Test
    void currentSnapshotDerivesOccludedAndLostFromClock() {
        associate(List.of(detection("g1", T0, 0, 0, 0.9)));
        timeService.setClock(Clock.fixed(T0.plusMillis(700), ZoneOffset.UTC));
        assertEquals("OCCLUDED", trackService.currentSnapshot("TRK-00000001").state());
        timeService.setClock(Clock.fixed(T0.plusMillis(3000), ZoneOffset.UTC));
        assertEquals("LOST", trackService.currentSnapshot("TRK-00000001").state());
        // 派生展示不写库、不推进版本。
        assertEquals(1, versionOf("TRK-00000001"));
    }

    // ---------- 辅助 ----------

    private AssociationResponse associate(List<DetectionRequest> detections) {
        return trackService.associate(new AssociationRequest(detections, null));
    }

    private long versionOf(String trackId) {
        Long version = jdbcTemplate.queryForObject(
                "SELECT version FROM track WHERE track_id = ?", Long.class, trackId);
        return version == null ? -1 : version;
    }

    private long count(String table, String where) {
        String sql = "SELECT COUNT(*) FROM " + table + (where == null ? "" : " WHERE " + where);
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private boolean rootCauseIs(Exception e, Class<? extends Throwable> type) {
        Throwable cause = e;
        while (cause != null) {
            if (type.isInstance(cause)) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static DetectionRequest detection(String id, Instant timestamp, double x, double y,
                                               double confidence) {
        return detection(id, timestamp, x, y, confidence, "pedestrian");
    }

    private static DetectionRequest detection(String id, Instant timestamp, double x, double y,
                                               double confidence, String category) {
        return new DetectionRequest(id, timestamp, "camera_front", category, x, y, confidence);
    }
}
