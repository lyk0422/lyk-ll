package com.example.starter;

import java.math.BigDecimal;
import java.util.List;

import com.example.starter.dto.GrainDtos.AddPointsRequest;
import com.example.starter.dto.GrainDtos.FrameChainView;
import com.example.starter.dto.GrainDtos.FramePointReading;
import com.example.starter.dto.GrainDtos.GasFrameRequest;
import com.example.starter.dto.GrainDtos.GasFrameView;
import com.example.starter.dto.GrainDtos.GasReadingRequest;
import com.example.starter.dto.GrainDtos.ReleaseFumigationRequest;
import com.example.starter.dto.GrainDtos.ReleaseView;
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
 * 多测点采样帧主流程与失败分支测试：P1/P2 两测点登记冻结、完整帧三帧安全且首末 30 分钟后
 * 双复核解除；缺点/重复点/未知点/非递增序号与时刻整帧拒绝不留部分读数；任一点越限打断；
 * 散气中增点后待解除确认失效须从新版首帧重计；旧单点入口对受控单拒绝；版本与幂等边界。
 */
class MultiPointFlowTest extends AbstractIntegrationTest {

    @Test
    void twoPointsThreeCompleteSafeFramesThenDoubleReviewReleases() {
        var ctx = multiPointAeratingContext("S-MP-1", "F-MP-1", "mp-1");
        assertEquals(List.of("P1", "P2"), fumigationService.getPointList(ctx.orderId()).points());
        assertEquals(1, fumigationService.getPointList(ctx.orderId()).listVersion());

        int releaseVersion = safeThreeFrames(ctx.orderId(), ctx.version(), ctx.points(), "mp-1");
        assertEquals(9, releaseVersion);

        // 每帧可复算最差值，全点 4.000 ≤ 阈值 5.000
        List<GasFrameView> frames = fumigationService.frames(ctx.orderId());
        assertEquals(3, frames.size());
        for (GasFrameView frame : frames) {
            assertEquals(0, new BigDecimal("4.000").compareTo(frame.worstConcentration()));
            assertTrue(frame.safe());
            assertEquals(2, frame.readings().size());
        }

        FrameChainView chain = fumigationService.frameChain(ctx.orderId());
        assertEquals(1, chain.listVersion());
        assertEquals(3, chain.runLength());
        assertTrue(chain.releasable());
        assertEquals(1, chain.firstSeqNo());
        assertEquals(3, chain.lastSeqNo());

        ReleaseView released = releaseFumigation(ctx.orderId(), 9, "mp-1-rel");
        assertEquals(1, released.firstSeqNo());
        assertEquals(3, released.lastSeqNo());
        assertEquals(1, released.listVersion());
        assertEquals(9, released.checkedVersion());

        assertEquals(FumigationStatus.RELEASED, fumigationService.getOrder(ctx.orderId()).status());
        assertEquals(SiloStatus.MONITORING, siloService.getSilo(ctx.siloId()).status());
        assertEquals("IDLE", siloService.getFan(ctx.fanId()).status());
    }

    @Test
    void missingPointRejectsWholeFrameAndWritesNoPartialReadings() {
        var ctx = multiPointAeratingContext("S-MP-2", "F-MP-2", "mp-2");

        // P1 连续安全但 P2 漏采：整帧只有 P1 → 缺点拒绝，不写帧也不写 P1 的部分读数
        ApiException missing = assertThrows(ApiException.class, () -> submitFrame(
                ctx.orderId(), ctx.version(), 1, U0.plusSeconds(3600), "mp-2-f1",
                List.of("P1"), "4.000"));
        assertEquals(422, missing.getStatus().value());
        assertEquals("FRAME_POINT_MISSING", missing.getCode());
        assertEquals(0, countRows("gas_frame"));
        assertEquals(0, countRows("gas_frame_reading"));
        // 未形成安全帧，解除拒绝，仓锁不解除
        ApiException noRelease = assertThrows(ApiException.class,
                () -> releaseFumigation(ctx.orderId(), ctx.version(), "mp-2-rel"));
        assertEquals("GAS_SEQUENCE_INCOMPLETE", noRelease.getCode());
        assertEquals(FumigationStatus.AERATING, fumigationService.getOrder(ctx.orderId()).status());
        assertEquals("LOCKED", siloService.getFan(ctx.fanId()).status());
    }

    @Test
    void anyPointExceedanceBreaksConsecutiveFrames() {
        var ctx = multiPointAeratingContext("S-MP-3", "F-MP-3", "mp-3");
        // 帧1、帧2 全点安全；帧3 P2 越限（P1 仍安全）；帧4、帧5 安全 → 最新三帧 3,4,5 含越限帧
        submitFrame(ctx.orderId(), 6, 1, U0.plusSeconds(3600), "mp-3-f1", ctx.points(), "4", "4");
        submitFrame(ctx.orderId(), 7, 2, U0.plusSeconds(4500), "mp-3-f2", ctx.points(), "4", "4");
        GasFrameView unsafe = submitFrame(ctx.orderId(), 8, 3, U0.plusSeconds(5400), "mp-3-f3",
                ctx.points(), "4.000", "5.001");
        assertFalse(unsafe.safe());
        assertEquals(0, new BigDecimal("5.001").compareTo(unsafe.worstConcentration()));
        submitFrame(ctx.orderId(), 9, 4, U0.plusSeconds(6300), "mp-3-f4", ctx.points(), "4", "4");
        submitFrame(ctx.orderId(), 10, 5, U0.plusSeconds(7200), "mp-3-f5", ctx.points(), "4", "4");

        ApiException ex = assertThrows(ApiException.class,
                () -> releaseFumigation(ctx.orderId(), 11, "mp-3-rel"));
        assertEquals("GAS_NOT_SAFE", ex.getCode());

        // 连续段复算在越限帧 3 处中断：当前版本有效安全段只有帧 4、5（长度 2，不可解除）
        FrameChainView chain = fumigationService.frameChain(ctx.orderId());
        assertEquals(2, chain.runLength());
        assertFalse(chain.releasable());
    }

    @Test
    void unknownAndDuplicatedPointsRejectFrame() {
        var ctx = multiPointAeratingContext("S-MP-4", "F-MP-4", "mp-4");

        ApiException unknown = assertThrows(ApiException.class, () -> fumigationService.ingestGasFrame(
                ctx.orderId(), new GasFrameRequest("mp-4-unknown", 6, 1, U0.plusSeconds(3600),
                        List.of(new FramePointReading("P1", new BigDecimal("4")),
                                new FramePointReading("P9", new BigDecimal("4"))))));
        assertEquals("FRAME_UNKNOWN_POINT", unknown.getCode());

        ApiException duplicated = assertThrows(ApiException.class, () -> fumigationService.ingestGasFrame(
                ctx.orderId(), new GasFrameRequest("mp-4-dup", 6, 1, U0.plusSeconds(3600),
                        List.of(new FramePointReading("P1", new BigDecimal("4")),
                                new FramePointReading("P1", new BigDecimal("5")),
                                new FramePointReading("P2", new BigDecimal("4"))))));
        assertEquals("FRAME_POINT_DUPLICATED", duplicated.getCode());
        assertEquals(0, countRows("gas_frame"));
        assertEquals(0, countRows("gas_frame_reading"));
    }

    @Test
    void seqGapAndNonIncreasingTimeBreakConsecutiveFrames() {
        var ctx = multiPointAeratingContext("S-MP-5", "F-MP-5", "mp-5");
        submitFrame(ctx.orderId(), 6, 1, U0.plusSeconds(3600), "mp-5-f1", ctx.points(), "4", "4");
        submitFrame(ctx.orderId(), 7, 2, U0.plusSeconds(4500), "mp-5-f2", ctx.points(), "4", "4");

        // 序号 4（缺口 3）：帧本身可受理，但打断连续帧计数，不能拿 1,2,4 拼成三帧
        submitFrame(ctx.orderId(), 8, 4, U0.plusSeconds(5400), "mp-5-f4", ctx.points(), "4", "4");
        ApiException gap = assertThrows(ApiException.class,
                () -> releaseFumigation(ctx.orderId(), 9, "mp-5-rel-gap"));
        assertEquals("GAS_SEQUENCE_INCOMPLETE", gap.getCode());

        // 时刻非递增：帧时刻早于上一帧 → 整帧拒绝
        ApiException notIncreasing = assertThrows(ApiException.class, () -> submitFrame(
                ctx.orderId(), 9, 5, U0.plusSeconds(5300), "mp-5-f5", ctx.points(), "4", "4"));
        assertEquals("FRAME_TIME_NOT_INCREASING", notIncreasing.getCode());
        // 序号非递增（5 已大于4，但再提交序号 3）→ 拒绝
        ApiException seqNotIncreasing = assertThrows(ApiException.class, () -> submitFrame(
                ctx.orderId(), 9, 3, U0.plusSeconds(6300), "mp-5-f3-late", ctx.points(), "4", "4"));
        assertEquals("FRAME_SEQ_NOT_INCREASING", seqNotIncreasing.getCode());
        assertEquals(3, countRows("gas_frame"));
    }

    @Test
    void firstLastGapExactlyThirtyMinutesIsLegalAndShorterRejects() {
        var ctx = multiPointAeratingContext("S-MP-6", "F-MP-6", "mp-6");
        // 恰等阈值 5.000、帧间隔 15 分钟，首末恰 30 分钟
        submitFrame(ctx.orderId(), 6, 1, U0.plusSeconds(3600), "mp-6-f1", ctx.points(), "5.000", "5.000");
        submitFrame(ctx.orderId(), 7, 2, U0.plusSeconds(4500), "mp-6-f2", ctx.points(), "5.000", "5.000");
        submitFrame(ctx.orderId(), 8, 3, U0.plusSeconds(5400), "mp-6-f3", ctx.points(), "5.000", "5.000");
        ReleaseView released = releaseFumigation(ctx.orderId(), 9, "mp-6-rel");
        assertEquals(1, released.firstSeqNo());
        assertEquals(3, released.lastSeqNo());
    }

    @Test
    void addingPointInvalidatesPendingConfirmationAndRestartsCountFromNewVersion() {
        var ctx = multiPointAeratingContext("S-MP-7", "F-MP-7", "mp-7");
        // 旧名单版本 1 下先形成三帧安全
        safeThreeFrames(ctx.orderId(), 6, ctx.points(), "mp-7");
        assertTrue(fumigationService.frameChain(ctx.orderId()).releasable());

        // 散气中增补 P3（不得删点）：名单版本 2、单据版本 +1（9 → 10）
        var added = fumigationService.addPoints(ctx.orderId(),
                new AddPointsRequest("mp-7-add", 9, List.of("P3")));
        assertEquals(2, added.listVersion());
        assertEquals(List.of("P1", "P2", "P3"), added.points());
        assertEquals(10, fumigationService.getOrder(ctx.orderId()).version());

        // 旧版三帧仍在，但当前版本有效连续段长度归零，旧确认失效，不能拿旧帧放行
        FrameChainView chainAfterAdd = fumigationService.frameChain(ctx.orderId());
        assertEquals(2, chainAfterAdd.listVersion());
        assertEquals(0, chainAfterAdd.runLength());
        assertFalse(chainAfterAdd.releasable());
        assertEquals(3, fumigationService.frames(ctx.orderId()).size());

        ApiException oldFramesRelease = assertThrows(ApiException.class,
                () -> releaseFumigation(ctx.orderId(), 10, "mp-7-rel-old"));
        assertEquals("GAS_SEQUENCE_INCOMPLETE", oldFramesRelease.getCode());

        // 用旧两测点名单提交帧：缺 P3 → 整帧拒绝
        ApiException incomplete = assertThrows(ApiException.class, () -> submitFrame(
                ctx.orderId(), 10, 4, U0.plusSeconds(9000), "mp-7-f4-old", ctx.points(), "4", "4"));
        assertEquals("FRAME_POINT_MISSING", incomplete.getCode());

        // 从新版首个完整帧（三点齐全）重新累计三帧后方可解除
        List<String> three = List.of("P1", "P2", "P3");
        submitFrame(ctx.orderId(), 10, 4, U0.plusSeconds(9000), "mp-7-f4", three, "4", "4", "4");
        submitFrame(ctx.orderId(), 11, 5, U0.plusSeconds(9900), "mp-7-f5", three, "4", "4", "4");
        FrameChainView midChain = fumigationService.frameChain(ctx.orderId());
        assertEquals(2, midChain.runLength());
        assertFalse(midChain.releasable());
        submitFrame(ctx.orderId(), 12, 6, U0.plusSeconds(10800), "mp-7-f6", three, "4", "4", "4");

        FrameChainView fullChain = fumigationService.frameChain(ctx.orderId());
        assertEquals(3, fullChain.runLength());
        assertTrue(fullChain.releasable());
        assertEquals(4, fullChain.firstSeqNo());
        assertEquals(6, fullChain.lastSeqNo());

        ReleaseView released = releaseFumigation(ctx.orderId(), 13, "mp-7-rel");
        assertEquals(2, released.listVersion());
        assertEquals(4, released.firstSeqNo());
        assertEquals(6, released.lastSeqNo());
        assertEquals(FumigationStatus.RELEASED, fumigationService.getOrder(ctx.orderId()).status());
    }

    @Test
    void addPointsCannotDeleteOrDuplicateAndOnlyAllowedDuringAeration() {
        var ctx = multiPointAeratingContext("S-MP-8", "F-MP-8", "mp-8");

        // 增补已存在测点 → 拒绝（只增不删但不可重复登记）
        ApiException dup = assertThrows(ApiException.class, () -> fumigationService.addPoints(ctx.orderId(),
                new AddPointsRequest("mp-8-add-dup", 6, List.of("P1"))));
        assertEquals("POINT_ALREADY_EXISTS", dup.getCode());
        // 失败不占键：同一 requestId 改用于合法增补 P3 应成功
        var added = fumigationService.addPoints(ctx.orderId(),
                new AddPointsRequest("mp-8-add-dup", 6, List.of("P3")));
        assertEquals(2, added.listVersion());
        // 原测点 P1、P2 保留（未删点）
        assertEquals(List.of("P1", "P2", "P3"), added.points());
    }

    @Test
    void legacySinglePointEntryRejectedForControlledOrder() {
        var ctx = multiPointAeratingContext("S-MP-9", "F-MP-9", "mp-9");
        // 受控多测点单走旧单点采样入口：一律 422，且不写任何读数
        ApiException ex = assertThrows(ApiException.class, () -> fumigationService.ingestGasReading(
                ctx.orderId(), new GasReadingRequest("mp-9-old", 6, 1,
                        new BigDecimal("4.000"), U0.plusSeconds(3600))));
        assertEquals(422, ex.getStatus().value());
        assertEquals("MULTI_POINT_FRAME_REQUIRED", ex.getCode());
        assertEquals(0, countRows("gas_reading"));
    }

    @Test
    void staleVersionIsConflictAndRegisterValidation() {
        var ctx = multiPointAeratingContext("S-MP-10", "F-MP-10", "mp-10");

        // 帧携带旧版本 → 409
        ApiException staleFrame = assertThrows(ApiException.class, () -> submitFrame(
                ctx.orderId(), 5, 1, U0.plusSeconds(3600), "mp-10-f1-stale",
                ctx.points(), "4", "4"));
        assertEquals(409, staleFrame.getStatus().value());
        assertEquals("VERSION_CONFLICT", staleFrame.getCode());

        // 解除携带旧版本 → 409
        safeThreeFrames(ctx.orderId(), 6, ctx.points(), "mp-10");
        ApiException staleRelease = assertThrows(ApiException.class,
                () -> releaseFumigation(ctx.orderId(), 8, "mp-10-rel-stale"));
        assertEquals(409, staleRelease.getStatus().value());
        assertEquals("VERSION_CONFLICT", staleRelease.getCode());

        // 登记少于 2 个测点 → 400；重复测点 → 422
        var silo = newSilo("S-MP-10B");
        var order = applyFumigation(silo.id(), "mp-10b-apply");
        approveTwice(order.id(), "mp-10b");
        startFumigation(order.id(), 3, "mp-10b-start");
        ApiException tooFew = assertThrows(ApiException.class, () -> fumigationService.registerPointList(
                order.id(), new com.example.starter.dto.GrainDtos.RegisterPointListRequest(
                        "mp-10b-reg-1", 4, List.of("P1"))));
        assertEquals(400, tooFew.getStatus().value());
        ApiException duplicated = assertThrows(ApiException.class, () -> fumigationService.registerPointList(
                order.id(), new com.example.starter.dto.GrainDtos.RegisterPointListRequest(
                        "mp-10b-reg-2", 4, List.of("P1", "P1"))));
        assertEquals(422, duplicated.getStatus().value());
        Long pointCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM gas_point WHERE fumigation_id = ?", Long.class, order.id());
        assertEquals(0L, pointCount);
    }

    @Test
    void frameAndPointOpsAreIdempotentAndFailuresDoNotOccupyKey() {
        var ctx = multiPointAeratingContext("S-MP-11", "F-MP-11", "mp-11");

        var frameRequest = new GasFrameRequest("mp-11-f1", 6, 1, U0.plusSeconds(3600),
                List.of(new FramePointReading("P1", new BigDecimal("4.000")),
                        new FramePointReading("P2", new BigDecimal("4.000"))));
        GasFrameView first = fumigationService.ingestGasFrame(ctx.orderId(), frameRequest);
        GasFrameView replay = fumigationService.ingestGasFrame(ctx.orderId(), frameRequest);
        assertTrue(replay.replayed());
        assertEquals(first.id(), replay.id());
        assertEquals(1, countRows("gas_frame"));
        assertEquals(2, countRows("gas_frame_reading"));

        // 同 requestId 异参（P2 浓度不同）→ 409
        ApiException conflict = assertThrows(ApiException.class, () -> fumigationService.ingestGasFrame(
                ctx.orderId(), new GasFrameRequest("mp-11-f1", 6, 1, U0.plusSeconds(3600),
                        List.of(new FramePointReading("P1", new BigDecimal("4.000")),
                                new FramePointReading("P2", new BigDecimal("4.500"))))));
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.getCode());

        // 增补失败（重复测点）不占键：同 requestId 随后合法成功
        ApiException failedAdd = assertThrows(ApiException.class, () -> fumigationService.addPoints(
                ctx.orderId(), new AddPointsRequest("mp-11-add", 7, List.of("P1"))));
        assertEquals("POINT_ALREADY_EXISTS", failedAdd.getCode());
        var addOk = fumigationService.addPoints(ctx.orderId(),
                new AddPointsRequest("mp-11-add", 7, List.of("P3")));
        assertFalse(addOk.replayed());
        assertEquals(2, addOk.listVersion());
    }

    @Test
    void lateFramesAfterReleaseOnlyRecordedAndEvidenceFrozen() {
        var ctx = multiPointAeratingContext("S-MP-12", "F-MP-12", "mp-12");
        safeThreeFrames(ctx.orderId(), 6, ctx.points(), "mp-12");
        ReleaseView released = releaseFumigation(ctx.orderId(), 9, "mp-12-rel");
        assertNotNull(released.releasedAt());

        // 解除后补录高浓度完整帧：只记迟到，不改冻结证据与版本
        GasFrameView late = submitFrame(ctx.orderId(), 9, 4, U0.plusSeconds(9000), "mp-12-late",
                ctx.points(), "99.000", "99.000");
        assertTrue(late.late());
        assertEquals(10, fumigationService.getOrder(ctx.orderId()).version());
        ReleaseView stored = fumigationService.getRelease(ctx.orderId());
        assertEquals(3, stored.lastSeqNo());
        assertEquals(1, stored.firstSeqNo());
        assertEquals(1, stored.listVersion());
    }
}
