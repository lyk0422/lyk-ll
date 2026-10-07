package com.example.starter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.example.starter.dto.GrainDtos.AddRosterPointsRequest;
import com.example.starter.dto.GrainDtos.FramePointReading;
import com.example.starter.dto.GrainDtos.GasFrameRequest;
import com.example.starter.dto.GrainDtos.GasFrameView;
import com.example.starter.dto.GrainDtos.GasReadingRequest;
import com.example.starter.dto.GrainDtos.GasSafetyView;
import com.example.starter.dto.GrainDtos.RegisterRosterRequest;
import com.example.starter.dto.GrainDtos.ReleaseFumigationRequest;
import com.example.starter.dto.GrainDtos.ReleaseView;
import com.example.starter.dto.GrainDtos.RosterView;
import com.example.starter.error.ApiException;
import com.example.starter.model.FumigationStatus;
import com.example.starter.model.SiloStatus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多测点受控熏蒸单流程测试：名单登记与冻结、采样帧全名单校验（缺点/重复点/未知点/
 * 非递增时刻全帧拒绝）、越限与序号缺口打断、30 分钟边界、增点失效重累计、
 * 旧单点入口拒绝、迟到帧与名单/帧幂等。
 */
class FumigationFrameFlowTest extends AbstractIntegrationTest {

    @Test
    void controlledHappyFlowReleasesWithTwoPointsThreeFrames() {
        var context = controlledAerating("S-MP-1", "F-MP-1", "mp-1");
        assertEquals(List.of("P1", "P2"), fumigationService.getRoster(context.orderId()).points());

        int version = safeThreeFrames(context.orderId(), context.version(), "mp-1");
        assertEquals(9, version);

        // 查询可复算每帧最差值与有效连续段
        List<GasFrameView> frames = fumigationService.gasFrames(context.orderId());
        assertEquals(3, frames.size());
        assertEquals(new BigDecimal("4.000"), frames.get(0).maxConcentration());
        assertTrue(frames.get(0).safe());
        assertEquals(List.of("P1", "P2"),
                frames.get(0).points().stream().map(p -> p.pointCode()).toList());
        GasSafetyView safety = fumigationService.gasSafety(context.orderId());
        assertEquals(3, safety.consecutiveSafeFrames());
        assertEquals(1, safety.firstSeqNo());
        assertEquals(3, safety.lastSeqNo());
        assertEquals(1800L, safety.gapSeconds());
        assertTrue(safety.releasable());

        ReleaseView released = releaseFumigation(context.orderId(), 9, "mp-1-rel");
        assertEquals(1, released.firstSeqNo());
        assertEquals(3, released.lastSeqNo());
        assertEquals(1, released.checkedRosterVersion());
        assertEquals(List.of("carol", "dave"), released.reviewers());

        var order = fumigationService.getOrder(context.orderId());
        assertEquals(FumigationStatus.RELEASED, order.status());
        assertEquals(10, order.version());
        assertEquals(SiloStatus.MONITORING, siloService.getSilo(context.siloId()).status());
        assertEquals("IDLE", siloService.getFan(context.fanId()).status());
    }

    @Test
    void missingPointFrameRejectedAtomicallyAndBlocksRelease() {
        var context = controlledAerating("S-MP-2", "F-MP-2", "mp-2");

        // P1 连续安全而 P2 漏采：缺点全帧拒绝，不写部分读数
        ApiException missing = assertThrows(ApiException.class,
                () -> ingestFrame(context.orderId(), "mp-2-f1", context.version(), 1, 3600,
                        "P1", "4.000"));
        assertEquals(422, missing.getStatus().value());
        assertEquals("FRAME_POINTS_MISMATCH", missing.getCode());
        assertEquals(0, countRows("gas_frame"));
        assertEquals(0, countRows("gas_frame_point"));
        assertEquals(context.version(), fumigationService.getOrder(context.orderId()).version());

        // 失败不占键：同一 requestId 补齐全名单后成功
        GasFrameView frame = ingestFrame(context.orderId(), "mp-2-f1", context.version(), 1, 3600,
                "P1", "4.000", "P2", "4.000");
        assertTrue(frame.safe());
        assertEquals(1, countRows("gas_frame"));
        assertEquals(2, countRows("gas_frame_point"));

        // 仅一帧完整，不足连续三帧，不能解除锁仓
        ApiException release = assertThrows(ApiException.class,
                () -> releaseFumigation(context.orderId(), context.version() + 1, "mp-2-rel"));
        assertEquals(422, release.getStatus().value());
        assertEquals("GAS_SEQUENCE_INCOMPLETE", release.getCode());
        assertEquals(FumigationStatus.AERATING, fumigationService.getOrder(context.orderId()).status());
        assertEquals("LOCKED", siloService.getFan(context.fanId()).status());
    }

    @Test
    void duplicateAndUnknownPointsRejected() {
        var context = controlledAerating("S-MP-3", "F-MP-3", "mp-3");

        ApiException duplicate = assertThrows(ApiException.class,
                () -> ingestFrame(context.orderId(), "mp-3-dup", context.version(), 1, 3600,
                        "P1", "4.000", "P1", "4.000", "P2", "4.000"));
        assertEquals(400, duplicate.getStatus().value());
        assertEquals("INVALID_FRAME_POINTS", duplicate.getCode());

        ApiException unknown = assertThrows(ApiException.class,
                () -> ingestFrame(context.orderId(), "mp-3-unknown", context.version(), 1, 3600,
                        "P1", "4.000", "P2", "4.000", "P9", "1.000"));
        assertEquals(422, unknown.getStatus().value());
        assertEquals("FRAME_POINTS_MISMATCH", unknown.getCode());
        assertEquals(0, countRows("gas_frame"));
    }

    @Test
    void frameTimeMustStrictlyIncrease() {
        var context = controlledAerating("S-MP-4", "F-MP-4", "mp-4");
        ingestFrame(context.orderId(), "mp-4-f1", context.version(), 1, 3600,
                "P1", "4.000", "P2", "4.000");

        ApiException same = assertThrows(ApiException.class,
                () -> ingestFrame(context.orderId(), "mp-4-f2", context.version() + 1, 2, 3600,
                        "P1", "4.000", "P2", "4.000"));
        assertEquals(422, same.getStatus().value());
        assertEquals("FRAME_TIME_NOT_INCREASING", same.getCode());

        ApiException backwards = assertThrows(ApiException.class,
                () -> ingestFrame(context.orderId(), "mp-4-f3", context.version() + 1, 2, 3500,
                        "P1", "4.000", "P2", "4.000"));
        assertEquals("FRAME_TIME_NOT_INCREASING", backwards.getCode());
        assertEquals(1, countRows("gas_frame"));
    }

    @Test
    void exceedanceOnAnyPointBreaksRun() {
        var context = controlledAerating("S-MP-5", "F-MP-5", "mp-5");
        ingestFrame(context.orderId(), "mp-5-f1", context.version(), 1, 3600,
                "P1", "4.000", "P2", "4.000");
        ingestFrame(context.orderId(), "mp-5-f2", context.version() + 1, 2, 4500,
                "P1", "4.000", "P2", "4.000");
        // P2 越限：帧仍完整入库但记为不安全，打断连续计数
        GasFrameView exceeded = ingestFrame(context.orderId(), "mp-5-f3", context.version() + 2, 3,
                5400, "P1", "4.000", "P2", "5.001");
        assertFalse(exceeded.safe());
        assertEquals(new BigDecimal("5.001"), exceeded.maxConcentration());
        ingestFrame(context.orderId(), "mp-5-f4", context.version() + 3, 4, 6300,
                "P1", "4.000", "P2", "4.000");
        ingestFrame(context.orderId(), "mp-5-f5", context.version() + 4, 5, 7200,
                "P1", "4.000", "P2", "4.000");

        GasSafetyView safety = fumigationService.gasSafety(context.orderId());
        assertEquals(2, safety.consecutiveSafeFrames());
        assertFalse(safety.releasable());

        ApiException ex = assertThrows(ApiException.class,
                () -> releaseFumigation(context.orderId(), context.version() + 5, "mp-5-rel"));
        assertEquals(422, ex.getStatus().value());
        assertEquals("GAS_NOT_SAFE", ex.getCode());
        assertEquals(FumigationStatus.AERATING, fumigationService.getOrder(context.orderId()).status());
    }

    @Test
    void seqGapBreaksRun() {
        var context = controlledAerating("S-MP-6", "F-MP-6", "mp-6");
        ingestFrame(context.orderId(), "mp-6-f1", context.version(), 1, 3600,
                "P1", "4.000", "P2", "4.000");
        ingestFrame(context.orderId(), "mp-6-f2", context.version() + 1, 2, 4500,
                "P1", "4.000", "P2", "4.000");
        // 序号缺口：缺 3 号，4 号帧时刻合法但连续段被打断
        ingestFrame(context.orderId(), "mp-6-f4", context.version() + 2, 4, 5400,
                "P1", "4.000", "P2", "4.000");

        GasSafetyView safety = fumigationService.gasSafety(context.orderId());
        assertEquals(1, safety.consecutiveSafeFrames());

        ApiException ex = assertThrows(ApiException.class,
                () -> releaseFumigation(context.orderId(), context.version() + 3, "mp-6-rel"));
        assertEquals(422, ex.getStatus().value());
        assertEquals("GAS_SEQUENCE_INCOMPLETE", ex.getCode());
    }

    @Test
    void equalThresholdAndExactlyThirtyMinutesAreLegal() {
        var context = controlledAerating("S-MP-7", "F-MP-7", "mp-7");
        // 恰等阈值 5.000、首末恰 30 分钟均合法
        ingestFrame(context.orderId(), "mp-7-f1", context.version(), 1, 3600,
                "P1", "5.000", "P2", "4.000");
        ingestFrame(context.orderId(), "mp-7-f2", context.version() + 1, 2, 4500,
                "P1", "4.000", "P2", "5.000");
        ingestFrame(context.orderId(), "mp-7-f3", context.version() + 2, 3, 5400,
                "P1", "5.000", "P2", "5.000");

        ReleaseView released = releaseFumigation(context.orderId(), context.version() + 3, "mp-7-rel");
        assertEquals(1, released.firstSeqNo());
        assertEquals(3, released.lastSeqNo());
        assertEquals(1, released.checkedRosterVersion());
    }

    @Test
    void gapShorterThanThirtyMinutesRejects() {
        var context = controlledAerating("S-MP-8", "F-MP-8", "mp-8");
        ingestFrame(context.orderId(), "mp-8-f1", context.version(), 1, 3600,
                "P1", "4.000", "P2", "4.000");
        ingestFrame(context.orderId(), "mp-8-f2", context.version() + 1, 2, 4499,
                "P1", "4.000", "P2", "4.000");
        ingestFrame(context.orderId(), "mp-8-f3", context.version() + 2, 3, 5399,
                "P1", "4.000", "P2", "4.000");

        ApiException ex = assertThrows(ApiException.class,
                () -> releaseFumigation(context.orderId(), context.version() + 3, "mp-8-rel"));
        assertEquals(422, ex.getStatus().value());
        assertEquals("AERATION_DURATION_SHORT", ex.getCode());
    }

    @Test
    void addPointInvalidatesPendingReleaseAndRequiresNewRun() {
        var context = controlledAerating("S-MP-9", "F-MP-9", "mp-9");
        safeThreeFrames(context.orderId(), context.version(), "mp-9");
        assertEquals(9, fumigationService.getOrder(context.orderId()).version());

        // 散气期间增测点 P3：生成名单版本 2，待解除确认失效
        RosterView rosterV2 = fumigationService.addRosterPoints(context.orderId(),
                new AddRosterPointsRequest("mp-9-add", 9, List.of("P3")));
        assertEquals(2, rosterV2.rosterVersion());
        assertEquals(List.of("P1", "P2", "P3"), rosterV2.points());
        assertEquals(10, fumigationService.getOrder(context.orderId()).version());

        // 旧版本解除 409；新版本解除也因新版名单无完整帧而拒绝
        ApiException stale = assertThrows(ApiException.class,
                () -> releaseFumigation(context.orderId(), 9, "mp-9-rel-stale"));
        assertEquals(409, stale.getStatus().value());
        assertEquals("VERSION_CONFLICT", stale.getCode());
        ApiException noRun = assertThrows(ApiException.class,
                () -> releaseFumigation(context.orderId(), 10, "mp-9-rel-early"));
        assertEquals(422, noRun.getStatus().value());
        assertEquals("GAS_SEQUENCE_INCOMPLETE", noRun.getCode());

        // 新版首个完整帧重新累计：旧帧 seq 1-3 保留但不参与，新帧 seq 4-6 覆盖三点
        ingestFrame(context.orderId(), "mp-9-f4", 10, 4, 6300,
                "P1", "4.000", "P2", "4.000", "P3", "4.000");
        ingestFrame(context.orderId(), "mp-9-f5", 11, 5, 7200,
                "P1", "4.000", "P2", "4.000", "P3", "4.000");
        ingestFrame(context.orderId(), "mp-9-f6", 12, 6, 8100,
                "P1", "4.000", "P2", "4.000", "P3", "4.000");

        ReleaseView released = releaseFumigation(context.orderId(), 13, "mp-9-rel");
        assertEquals(4, released.firstSeqNo());
        assertEquals(6, released.lastSeqNo());
        assertEquals(2, released.checkedRosterVersion());

        // 旧读数保留：全部 6 帧可查询，旧帧仍按名单版本 1 记录
        List<GasFrameView> frames = fumigationService.gasFrames(context.orderId());
        assertEquals(6, frames.size());
        assertEquals(1, frames.get(0).rosterVersion());
        assertEquals(2, frames.get(5).rosterVersion());
    }

    @Test
    void cannotBackfillOldFramesToFakeContinuity() {
        var context = controlledAerating("S-MP-10", "F-MP-10", "mp-10");
        safeThreeFrames(context.orderId(), context.version(), "mp-10");
        fumigationService.addRosterPoints(context.orderId(),
                new AddRosterPointsRequest("mp-10-add", 9, List.of("P3")));

        // 回填旧时刻造连续性：非递增时刻全帧拒绝
        ApiException backfill = assertThrows(ApiException.class,
                () -> ingestFrame(context.orderId(), "mp-10-backfill", 10, 4, 5400,
                        "P1", "4.000", "P2", "4.000", "P3", "4.000"));
        assertEquals(422, backfill.getStatus().value());
        assertEquals("FRAME_TIME_NOT_INCREASING", backfill.getCode());

        // 旧序号同样不可重用
        ApiException dupSeq = assertThrows(ApiException.class,
                () -> ingestFrame(context.orderId(), "mp-10-dupseq", 10, 3, 6300,
                        "P1", "4.000", "P2", "4.000", "P3", "4.000"));
        assertEquals(422, dupSeq.getStatus().value());
        assertEquals("SEQ_NO_DUPLICATED", dupSeq.getCode());
        assertEquals(3, countRows("gas_frame"));
    }

    @Test
    void oldSinglePointEndpointRejectsControlledOrder() {
        var context = controlledAerating("S-MP-11", "F-MP-11", "mp-11");

        ApiException ex = assertThrows(ApiException.class,
                () -> fumigationService.ingestGasReading(context.orderId(),
                        new GasReadingRequest("mp-11-single", context.version(), 1,
                                new BigDecimal("4.000"), U0.plusSeconds(3600))));
        assertEquals(422, ex.getStatus().value());
        assertEquals("MULTI_POINT_REQUIRED", ex.getCode());
        assertEquals(0, countRows("gas_reading"));
    }

    @Test
    void frameEndpointRejectsNonControlledOrder() {
        var silo = newSilo("S-MP-12");
        var fan = newFan("F-MP-12");
        var order = applyFumigation(silo.id(), "mp-12-apply");
        approveTwice(order.id(), "mp-12");
        startFumigation(order.id(), 3, "mp-12-start");
        beginAeration(order.id(), 4, fan.id(), "mp-12-aer");

        ApiException ex = assertThrows(ApiException.class,
                () -> ingestFrame(order.id(), "mp-12-f1", 5, 1, 3600, "P1", "4.000", "P2", "4.000"));
        assertEquals(422, ex.getStatus().value());
        assertEquals("ROSTER_NOT_REGISTERED", ex.getCode());
        assertEquals(0, countRows("gas_frame"));
    }

    @Test
    void rosterRegistrationRules() {
        var silo = newSilo("S-MP-13");
        var order = applyFumigation(silo.id(), "mp-13-apply");

        // 少于 2 点、重复点均拒绝
        ApiException tooFew = assertThrows(ApiException.class,
                () -> fumigationService.registerRoster(order.id(),
                        new RegisterRosterRequest("mp-13-few", 1, List.of("P1"))));
        assertEquals(400, tooFew.getStatus().value());
        assertEquals("INVALID_POINTS", tooFew.getCode());
        ApiException dup = assertThrows(ApiException.class,
                () -> fumigationService.registerRoster(order.id(),
                        new RegisterRosterRequest("mp-13-dup", 1, List.of("P1", "P1"))));
        assertEquals("INVALID_POINTS", dup.getCode());

        // 失败不占键：同 requestId 修正参数后成功
        RosterView roster = fumigationService.registerRoster(order.id(),
                new RegisterRosterRequest("mp-13-dup", 1, List.of("P1", "P2")));
        assertEquals(1, roster.rosterVersion());

        // 重复登记拒绝（只能增点）
        ApiException again = assertThrows(ApiException.class,
                () -> fumigationService.registerRoster(order.id(),
                        new RegisterRosterRequest("mp-13-again", 2, List.of("P4", "P5"))));
        assertEquals(422, again.getStatus().value());
        assertEquals("ROSTER_ALREADY_EXISTS", again.getCode());

        // 散气后禁止再登记初始名单
        var silo2 = newSilo("S-MP-13B");
        var fan2 = newFan("F-MP-13B");
        var order2 = applyFumigation(silo2.id(), "mp-13b-apply");
        approveTwice(order2.id(), "mp-13b");
        startFumigation(order2.id(), 3, "mp-13b-start");
        beginAeration(order2.id(), 4, fan2.id(), "mp-13b-aer");
        ApiException tooLate = assertThrows(ApiException.class,
                () -> fumigationService.registerRoster(order2.id(),
                        new RegisterRosterRequest("mp-13b-roster", 5, List.of("P1", "P2"))));
        assertEquals(422, tooLate.getStatus().value());
        assertEquals("INVALID_ORDER_STATE", tooLate.getCode());
    }

    @Test
    void addPointRules() {
        var context = controlledAerating("S-MP-14", "F-MP-14", "mp-14");

        // 已存在的测点不能重复增
        ApiException exists = assertThrows(ApiException.class,
                () -> fumigationService.addRosterPoints(context.orderId(),
                        new AddRosterPointsRequest("mp-14-exists", context.version(), List.of("P1"))));
        assertEquals(422, exists.getStatus().value());
        assertEquals("POINT_ALREADY_EXISTS", exists.getCode());

        // 总数超过 10 拒绝
        ApiException tooMany = assertThrows(ApiException.class,
                () -> fumigationService.addRosterPoints(context.orderId(),
                        new AddRosterPointsRequest("mp-14-many", context.version(),
                                List.of("P3", "P4", "P5", "P6", "P7", "P8", "P9", "P10", "P11"))));
        assertEquals(422, tooMany.getStatus().value());
        assertEquals("ROSTER_TOO_LARGE", tooMany.getCode());

        // 未登记名单不能增点
        var plain = applyFumigation(newSilo("S-MP-14B").id(), "mp-14b-apply");
        ApiException noRoster = assertThrows(ApiException.class,
                () -> fumigationService.addRosterPoints(plain.id(),
                        new AddRosterPointsRequest("mp-14b-add", 1, List.of("P1"))));
        assertEquals(422, noRoster.getStatus().value());
        assertEquals("ROSTER_NOT_REGISTERED", noRoster.getCode());

        // 解除后禁止增点
        safeThreeFrames(context.orderId(), context.version(), "mp-14");
        releaseFumigation(context.orderId(), context.version() + 3, "mp-14-rel");
        ApiException released = assertThrows(ApiException.class,
                () -> fumigationService.addRosterPoints(context.orderId(),
                        new AddRosterPointsRequest("mp-14-late", 10, List.of("P3"))));
        assertEquals(422, released.getStatus().value());
        assertEquals("INVALID_ORDER_STATE", released.getCode());
    }

    @Test
    void rosterAndFrameIdempotencyReplayAndConflict() {
        var context = controlledAerating("S-MP-15", "F-MP-15", "mp-15");

        // 帧同参重放、异参 409
        var frameRequest = new GasFrameRequest("mp-15-f1", context.version(), 1,
                U0.plusSeconds(3600),
                List.of(new FramePointReading("P1", new BigDecimal("4.000")),
                        new FramePointReading("P2", new BigDecimal("4.000"))));
        GasFrameView first = fumigationService.ingestGasFrame(context.orderId(), frameRequest);
        GasFrameView replay = fumigationService.ingestGasFrame(context.orderId(), frameRequest);
        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.id(), replay.id());
        assertEquals(1, countRows("gas_frame"));

        ApiException conflict = assertThrows(ApiException.class,
                () -> fumigationService.ingestGasFrame(context.orderId(),
                        new GasFrameRequest("mp-15-f1", context.version(), 1, U0.plusSeconds(3600),
                                List.of(new FramePointReading("P1", new BigDecimal("4.500")),
                                        new FramePointReading("P2", new BigDecimal("4.000"))))));
        assertEquals(409, conflict.getStatus().value());
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.getCode());
        assertEquals(1, countRows("gas_frame"));

        // 名单增点同参重放、异参 409
        var addRequest = new AddRosterPointsRequest("mp-15-add", context.version() + 1, List.of("P3"));
        RosterView added = fumigationService.addRosterPoints(context.orderId(), addRequest);
        RosterView addedReplay = fumigationService.addRosterPoints(context.orderId(), addRequest);
        assertFalse(added.replayed());
        assertTrue(addedReplay.replayed());
        assertEquals(added.id(), addedReplay.id());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM fumigation_roster WHERE fumigation_id = ? AND roster_version = 2",
                Integer.class, context.orderId()));

        ApiException addConflict = assertThrows(ApiException.class,
                () -> fumigationService.addRosterPoints(context.orderId(),
                        new AddRosterPointsRequest("mp-15-add", context.version() + 1, List.of("P4"))));
        assertEquals(409, addConflict.getStatus().value());
        assertEquals("IDEMPOTENCY_CONFLICT", addConflict.getCode());
    }

    @Test
    void lateFramesAfterReleaseOnlyRecorded() {
        var context = controlledAerating("S-MP-16", "F-MP-16", "mp-16");
        safeThreeFrames(context.orderId(), context.version(), "mp-16");
        releaseFumigation(context.orderId(), context.version() + 3, "mp-16-rel");
        assertEquals(10, fumigationService.getOrder(context.orderId()).version());

        // 解除后帧只记迟到证据：不改版本与冻结证据
        GasFrameView late = ingestFrame(context.orderId(), "mp-16-late", 10, 4, 9000,
                "P1", "99.000", "P2", "99.000");
        assertTrue(late.late());
        assertFalse(late.safe());
        assertEquals(10, fumigationService.getOrder(context.orderId()).version());

        List<GasFrameView> frames = fumigationService.gasFrames(context.orderId());
        assertEquals(4, frames.size());
        assertFalse(frames.get(0).late());
        assertTrue(frames.get(3).late());

        ReleaseView stored = fumigationService.getRelease(context.orderId());
        assertEquals(3, stored.lastSeqNo());
        assertEquals(1, stored.firstSeqNo());
        assertNotNull(stored.checkedRosterVersion());
    }

    /** 建仓建风机、申请、双审批、登记 P1/P2 名单、开始、转散气，返回上下文（版本 6）。 */
    private ControlledContext controlledAerating(String siloCode, String fanCode, String prefix) {
        var silo = newSilo(siloCode);
        var fan = newFan(fanCode);
        var order = applyFumigation(silo.id(), prefix + "-apply");
        approveTwice(order.id(), prefix);
        RosterView roster = fumigationService.registerRoster(order.id(),
                new RegisterRosterRequest(prefix + "-roster", 3, List.of("P1", "P2")));
        assertEquals(1, roster.rosterVersion());
        startFumigation(order.id(), 4, prefix + "-start");
        beginAeration(order.id(), 5, fan.id(), prefix + "-aer");
        return new ControlledContext(order.id(), silo.id(), fan.id(), 6);
    }

    /** 上报连续三帧安全帧（P1/P2 均 4.000ppm），首末恰间隔 30 分钟，返回最新版本。 */
    private int safeThreeFrames(long orderId, int version, String prefix) {
        ingestFrame(orderId, prefix + "-f1", version, 1, 3600, "P1", "4.000", "P2", "4.000");
        ingestFrame(orderId, prefix + "-f2", version + 1, 2, 4500, "P1", "4.000", "P2", "4.000");
        ingestFrame(orderId, prefix + "-f3", version + 2, 3, 5400, "P1", "4.000", "P2", "4.000");
        return version + 3;
    }

    /** 按“测点、浓度”成对参数上报一帧。 */
    private GasFrameView ingestFrame(long orderId, String requestId, int version, int seq,
                                     long secondsFromU0, String... pointConc) {
        List<FramePointReading> readings = new ArrayList<>();
        for (int i = 0; i < pointConc.length; i += 2) {
            readings.add(new FramePointReading(pointConc[i], new BigDecimal(pointConc[i + 1])));
        }
        return fumigationService.ingestGasFrame(orderId,
                new GasFrameRequest(requestId, version, seq, U0.plusSeconds(secondsFromU0), readings));
    }

    private record ControlledContext(long orderId, long siloId, long fanId, int version) {
    }
}
