package com.example.starter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.example.starter.dto.FumigationDtos.ApproveRequest;
import com.example.starter.dto.FumigationDtos.CreateFumigationRequest;
import com.example.starter.dto.FumigationDtos.FinishFumigationRequest;
import com.example.starter.dto.FumigationDtos.FumigationView;
import com.example.starter.dto.FumigationDtos.GasReadingRequest;
import com.example.starter.dto.FumigationDtos.GasReadingResponse;
import com.example.starter.dto.FumigationDtos.ReleaseConfirmRequest;
import com.example.starter.dto.FumigationDtos.StartFumigationRequest;
import com.example.starter.dto.GrainDtos.CreateJobRequest;
import com.example.starter.dto.GrainDtos.FanView;
import com.example.starter.dto.GrainDtos.JobView;
import com.example.starter.dto.GrainDtos.SiloView;
import com.example.starter.error.ApiException;
import com.example.starter.model.FumigationStatus;
import com.example.starter.model.SiloStatus;
import com.example.starter.model.VentilationPurpose;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 熏蒸作业测试：申请窗互斥、双审批、开始联锁、散气采样规则、双复核解除、
 * 版本乐观锁与 requestId 幂等（失败不占键）、解除后迟到读数。
 */
class FumigationTest extends AbstractIntegrationTest {

    /** 熏蒸窗基准时刻（UTC）：2026-09-26T02:00Z，即 Asia/Shanghai 10:00 */
    private static final Instant W0 = Instant.parse("2026-09-26T02:00:00Z");

    private static final BigDecimal THRESHOLD = new BigDecimal("50.0000");

    // ---------- 主流程 ----------

    @Test
    void fullFlowLocksAndReleasesAtomically() {
        SiloView silo = newSilo("S-FULL");
        FanView fan = newFan("F-FULL");

        FumigationView created = createFumigation(silo.id(), "fm-full", W0, W0.plusSeconds(7200));
        assertEquals(FumigationStatus.PENDING_APPROVAL, created.status());
        assertEquals(1, created.version());
        assertFalse(created.replayed());
        assertEquals(List.of("密封粮堆", "投放合成药剂", "密闭熏蒸"), created.planSteps());

        FumigationView approved1 = fumigationService.approve(created.id(),
                new ApproveRequest("fa-full-1", 1, "审批人甲"));
        assertEquals(FumigationStatus.PENDING_APPROVAL, approved1.status());
        assertEquals(2, approved1.version());

        FumigationView approved2 = fumigationService.approve(created.id(),
                new ApproveRequest("fa-full-2", 2, "审批人乙"));
        assertEquals(FumigationStatus.APPROVED, approved2.status());
        assertEquals(3, approved2.version());
        assertEquals(List.of("审批人甲", "审批人乙"), approved2.approvers());

        FumigationView started = fumigationService.start(created.id(),
                new StartFumigationRequest("fs-full", 3));
        assertEquals(FumigationStatus.FUMIGATING, started.status());
        assertEquals(4, started.version());
        assertEquals(3, started.frozenVersion());
        assertNotNull(started.startedAt());
        assertEquals(SiloStatus.FUMIGATING, siloService.getSilo(silo.id()).status());

        FumigationView aerating = fumigationService.finish(created.id(),
                new FinishFumigationRequest("ff-full", 4, fan.id()));
        assertEquals(FumigationStatus.AERATING, aerating.status());
        assertEquals(5, aerating.version());
        assertEquals(fan.id(), aerating.fanId());
        assertEquals("LOCKED", siloService.getFan(fan.id()).status());

        sample(created.id(), "fg-full-1", 1, "40.00", W0);
        sample(created.id(), "fg-full-2", 2, "30.00", W0.plusSeconds(20 * 60));
        sample(created.id(), "fg-full-3", 3, "20.00", W0.plusSeconds(40 * 60));

        FumigationView confirmed1 = fumigationService.confirmRelease(created.id(),
                new ReleaseConfirmRequest("fr-full-1", 5, "复核人甲"));
        assertEquals(FumigationStatus.AERATING, confirmed1.status());
        assertEquals(6, confirmed1.version());
        assertEquals("复核人甲", confirmed1.releaseReviewer1());

        FumigationView released = fumigationService.confirmRelease(created.id(),
                new ReleaseConfirmRequest("fr-full-2", 6, "复核人乙"));
        assertEquals(FumigationStatus.RELEASED, released.status());
        assertEquals(7, released.version());
        assertNotNull(released.releasedAt());

        // 原子释放：仓锁恢复锁仓前状态，风机释放
        assertEquals(SiloStatus.MONITORING, siloService.getSilo(silo.id()).status());
        assertEquals("IDLE", siloService.getFan(fan.id()).status());
    }

    @Test
    void releaseRestoresPreviousSiloStatus() {
        SiloView silo = newSilo("S-PREV");
        FanView fan = newFan("F-PREV");
        raiseAlarm(silo.id(), "r-prev-alarm");

        FumigationView f = createFumigation(silo.id(), "fm-prev", W0, W0.plusSeconds(3600));
        approveTwice(f.id(), "prev");
        fumigationService.start(f.id(), new StartFumigationRequest("fs-prev", 3));
        assertEquals(SiloStatus.FUMIGATING, siloService.getSilo(silo.id()).status());
        fumigationService.finish(f.id(), new FinishFumigationRequest("ff-prev", 4, fan.id()));
        sample(f.id(), "fg-prev-1", 1, "10.00", W0);
        sample(f.id(), "fg-prev-2", 2, "10.00", W0.plusSeconds(20 * 60));
        sample(f.id(), "fg-prev-3", 3, "10.00", W0.plusSeconds(40 * 60));
        fumigationService.confirmRelease(f.id(), new ReleaseConfirmRequest("fr-prev-1", 5, "复核人甲"));
        fumigationService.confirmRelease(f.id(), new ReleaseConfirmRequest("fr-prev-2", 6, "复核人乙"));

        assertEquals(SiloStatus.ALARMING, siloService.getSilo(silo.id()).status());
    }

    // ---------- 申请与作业窗互斥 ----------

    @Test
    void createRejectsInvalidWindowAndMissingSilo() {
        SiloView silo = newSilo("S-INV");

        ApiException badWindow = assertThrows(ApiException.class, () -> createFumigation(
                silo.id(), "fm-inv-1", W0.plusSeconds(3600), W0));
        assertEquals(400, badWindow.getStatus().value());
        assertEquals("INVALID_WINDOW", badWindow.getCode());

        ApiException negativeThreshold = assertThrows(ApiException.class, () ->
                fumigationService.create(silo.id(), new CreateFumigationRequest("fm-inv-2",
                        W0, W0.plusSeconds(3600), new BigDecimal("-0.01"), List.of("密封"))));
        assertEquals(400, negativeThreshold.getStatus().value());
        assertEquals("INVALID_THRESHOLD", negativeThreshold.getCode());

        ApiException missing = assertThrows(ApiException.class, () -> createFumigation(
                999999L, "fm-inv-3", W0, W0.plusSeconds(3600)));
        assertEquals(404, missing.getStatus().value());
        assertEquals("SILO_NOT_FOUND", missing.getCode());

        // 失败不占键：修正参数后同键可成功
        FumigationView retried = createFumigation(silo.id(), "fm-inv-1", W0, W0.plusSeconds(3600));
        assertEquals(1, retried.version());
        assertEquals(1, countRows("fumigation"));
    }

    @Test
    void createOnArchivedSiloIsRejected() {
        SiloView silo = newSilo("S-ARC");
        jdbc.update("UPDATE silo SET status = 'ARCHIVED' WHERE id = ?", silo.id());

        ApiException ex = assertThrows(ApiException.class, () -> createFumigation(
                silo.id(), "fm-arc", W0, W0.plusSeconds(3600)));
        assertEquals(422, ex.getStatus().value());
        assertEquals("SILO_ARCHIVED", ex.getCode());
    }

    @Test
    void fumigationWindowConflictsWithVentilationWindowButTouchingAllowed() {
        SiloView silo = newSilo("S-FWC");
        FanView fan = newFan("F-FWC");
        raiseAlarm(silo.id(), "r-fwc-alarm");
        // 通风窗 10:00-11:00 Asia/Shanghai = 02:00-03:00 UTC
        ventilationService.createJob(silo.id(), newJobRequest("j-fwc-1", fan.id()));

        // 熏蒸窗 02:30-03:30 UTC 与通风窗重叠 → 422
        ApiException ex = assertThrows(ApiException.class, () -> createFumigation(
                silo.id(), "fm-fwc-1", W0.plusSeconds(1800), W0.plusSeconds(5400)));
        assertEquals(422, ex.getStatus().value());
        assertEquals("WINDOW_CONFLICT", ex.getCode());

        // 熏蒸窗 03:00-04:00 UTC 与通风窗端点相接 → 允许
        FumigationView adjacent = createFumigation(silo.id(), "fm-fwc-2",
                W0.plusSeconds(3600), W0.plusSeconds(7200));
        assertEquals(1, adjacent.version());
    }

    @Test
    void ventilationWindowConflictsWithFumigationWindowButTouchingAllowed() {
        SiloView silo = newSilo("S-VWC");
        FanView fan = newFan("F-VWC");
        // 熏蒸窗 02:00-03:00 UTC = 10:00-11:00 Asia/Shanghai
        createFumigation(silo.id(), "fm-vwc", W0, W0.plusSeconds(3600));

        raiseAlarm(silo.id(), "r-vwc-alarm");
        // 通风窗 10:30-11:30 Asia/Shanghai 与熏蒸窗重叠 → 409（旧入口语义不变）
        ApiException ex = assertThrows(ApiException.class, () -> ventilationService.createJob(silo.id(),
                new CreateJobRequest("j-vwc-1", fan.id(), VentilationPurpose.COOLING,
                        T0.plusHours(2).plusMinutes(30), T0.plusHours(3).plusMinutes(30),
                        new BigDecimal("65.00"))));
        assertEquals(409, ex.getStatus().value());
        assertEquals("WINDOW_CONFLICT", ex.getCode());

        // 通风窗 11:00-12:00 Asia/Shanghai 与熏蒸窗端点相接 → 允许
        JobView adjacent = ventilationService.createJob(silo.id(), new CreateJobRequest(
                "j-vwc-2", fan.id(), VentilationPurpose.COOLING,
                T0.plusHours(3), T0.plusHours(4), new BigDecimal("65.00")));
        assertEquals(1, adjacent.roundNo());
    }

    @Test
    void fumigationWindowsOfSameSiloAreMutuallyExclusive() {
        SiloView silo = newSilo("S-FFW");
        createFumigation(silo.id(), "fm-ffw-1", W0, W0.plusSeconds(3600));

        ApiException ex = assertThrows(ApiException.class, () -> createFumigation(
                silo.id(), "fm-ffw-2", W0.plusSeconds(1800), W0.plusSeconds(5400)));
        assertEquals(422, ex.getStatus().value());
        assertEquals("WINDOW_CONFLICT", ex.getCode());

        FumigationView adjacent = createFumigation(silo.id(), "fm-ffw-3",
                W0.plusSeconds(3600), W0.plusSeconds(7200));
        assertEquals(2, countRows("fumigation"));
        assertEquals(1, adjacent.version());
    }

    // ---------- 审批 ----------

    @Test
    void approveRequiresTwoDistinctApprovers() {
        SiloView silo = newSilo("S-APR");
        FumigationView f = createFumigation(silo.id(), "fm-apr", W0, W0.plusSeconds(3600));

        fumigationService.approve(f.id(), new ApproveRequest("fa-apr-1", 1, "审批人甲"));
        ApiException duplicate = assertThrows(ApiException.class, () ->
                fumigationService.approve(f.id(), new ApproveRequest("fa-apr-2", 2, "审批人甲")));
        assertEquals(422, duplicate.getStatus().value());
        assertEquals("DUPLICATE_APPROVER", duplicate.getCode());

        // 失败不占键：换不同审批人后同键可成功
        FumigationView approved = fumigationService.approve(f.id(),
                new ApproveRequest("fa-apr-2", 2, "审批人乙"));
        assertEquals(FumigationStatus.APPROVED, approved.status());

        // 已批准后不再接受审批
        ApiException state = assertThrows(ApiException.class, () ->
                fumigationService.approve(f.id(), new ApproveRequest("fa-apr-3", 3, "审批人丙")));
        assertEquals(422, state.getStatus().value());
        assertEquals("INVALID_STATE", state.getCode());
    }

    @Test
    void approveVersionConflictAndFailedRequestDoesNotOccupyKey() {
        SiloView silo = newSilo("S-VER");
        FumigationView f = createFumigation(silo.id(), "fm-ver", W0, W0.plusSeconds(3600));

        ApiException conflict = assertThrows(ApiException.class, () ->
                fumigationService.approve(f.id(), new ApproveRequest("fa-ver-1", 5, "审批人甲")));
        assertEquals(409, conflict.getStatus().value());
        assertEquals("VERSION_CONFLICT", conflict.getCode());

        // 失败不占键：同一 requestId 携带正确版本可成功（CREATE 与 APPROVE 各占一键）
        FumigationView approved = fumigationService.approve(f.id(),
                new ApproveRequest("fa-ver-1", 1, "审批人甲"));
        assertEquals(2, approved.version());
        assertEquals(2, countRows("fumigation_request"));
    }

    @Test
    void approveIdempotentReplayAndParamConflict() {
        SiloView silo = newSilo("S-IDM");
        FumigationView f = createFumigation(silo.id(), "fm-idm", W0, W0.plusSeconds(3600));

        FumigationView first = fumigationService.approve(f.id(),
                new ApproveRequest("fa-idm-1", 1, "审批人甲"));
        // 推进到更高版本，验证重放返回首次冻结结果
        fumigationService.approve(f.id(), new ApproveRequest("fa-idm-2", 2, "审批人乙"));

        FumigationView replayed = fumigationService.approve(f.id(),
                new ApproveRequest("fa-idm-1", 1, "审批人甲"));
        assertTrue(replayed.replayed());
        assertEquals(first.version(), replayed.version());
        assertEquals(FumigationStatus.PENDING_APPROVAL, replayed.status());
        assertEquals(3, fumigationService.getFumigation(f.id()).version());
        assertEquals(2, countRows("fumigation_approval"));

        ApiException conflict = assertThrows(ApiException.class, () ->
                fumigationService.approve(f.id(), new ApproveRequest("fa-idm-1", 1, "审批人丙")));
        assertEquals(409, conflict.getStatus().value());
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.getCode());
    }

    // ---------- 开始联锁 ----------

    @Test
    void startRequiresApprovalAndRechecksExecutingVentilation() {
        SiloView silo = newSilo("S-STV");
        FanView fan = newFan("F-STV");
        raiseAlarm(silo.id(), "r-stv-alarm");
        // 通风窗 10:00-11:00 Asia/Shanghai = 02:00-03:00 UTC，执行中
        ventilationService.createJob(silo.id(), newJobRequest("j-stv-1", fan.id()));

        // 熏蒸窗 04:00-05:00 UTC，与通风窗不重叠
        FumigationView f = createFumigation(silo.id(), "fm-stv",
                W0.plusSeconds(7200), W0.plusSeconds(10800));

        ApiException notApproved = assertThrows(ApiException.class, () ->
                fumigationService.start(f.id(), new StartFumigationRequest("fs-stv-0", 1)));
        assertEquals(422, notApproved.getStatus().value());
        assertEquals("INVALID_STATE", notApproved.getCode());

        approveTwice(f.id(), "stv");
        // 实际开始时再查：存在执行中通风 → 422
        ApiException executing = assertThrows(ApiException.class, () ->
                fumigationService.start(f.id(), new StartFumigationRequest("fs-stv", 3)));
        assertEquals(422, executing.getStatus().value());
        assertEquals("VENTILATION_EXECUTING", executing.getCode());

        // 通风完成后可开始
        JobView job = ventilationService.windows(silo.id()).get(0);
        ventilationService.completeJob(job.id());
        FumigationView started = fumigationService.start(f.id(), new StartFumigationRequest("fs-stv", 3));
        assertEquals(FumigationStatus.FUMIGATING, started.status());
        assertEquals(SiloStatus.FUMIGATING, siloService.getSilo(silo.id()).status());
    }

    @Test
    void startedFumigationBlocksVentilationOnSameSilo() {
        SiloView silo = newSilo("S-BLK");
        raiseAlarm(silo.id(), "r-blk-alarm");
        FumigationView f = createFumigation(silo.id(), "fm-blk", W0, W0.plusSeconds(3600));
        approveTwice(f.id(), "blk");
        fumigationService.start(f.id(), new StartFumigationRequest("fs-blk", 3));

        // 熏蒸期间禁止普通通风（旧入口拒绝，语义不变）
        FanView fan = newFan("F-BLK");
        ApiException ex = assertThrows(ApiException.class, () -> ventilationService.createJob(silo.id(),
                new CreateJobRequest("j-blk-1", fan.id(), VentilationPurpose.COOLING,
                        T0.plusHours(5), T0.plusHours(6), new BigDecimal("65.00"))));
        assertEquals(409, ex.getStatus().value());
        assertEquals(0, countRows("ventilation_job"));
    }

    // ---------- 结束转散气 ----------

    @Test
    void finishRequiresIdleFan() {
        SiloView siloA = newSilo("S-FNA");
        SiloView siloB = newSilo("S-FNB");
        FanView fan = newFan("F-FN");
        FumigationView fa = createFumigation(siloA.id(), "fm-fna", W0, W0.plusSeconds(3600));
        FumigationView fb = createFumigation(siloB.id(), "fm-fnb", W0, W0.plusSeconds(3600));
        approveTwice(fa.id(), "fna");
        approveTwice(fb.id(), "fnb");
        fumigationService.start(fa.id(), new StartFumigationRequest("fs-fna", 3));
        fumigationService.start(fb.id(), new StartFumigationRequest("fs-fnb", 3));

        fumigationService.finish(fa.id(), new FinishFumigationRequest("ff-fna", 4, fan.id()));
        ApiException busy = assertThrows(ApiException.class, () ->
                fumigationService.finish(fb.id(), new FinishFumigationRequest("ff-fnb", 4, fan.id())));
        assertEquals(422, busy.getStatus().value());
        assertEquals("FAN_BUSY", busy.getCode());
        assertEquals(FumigationStatus.FUMIGATING, fumigationService.getFumigation(fb.id()).status());

        ApiException missingFan = assertThrows(ApiException.class, () ->
                fumigationService.finish(fb.id(), new FinishFumigationRequest("ff-fnb2", 4, 999999L)));
        assertEquals(404, missingFan.getStatus().value());
        assertEquals("FAN_NOT_FOUND", missingFan.getCode());
    }

    // ---------- 散气采样 ----------

    @Test
    void gasReadingValidatesSeqConcentrationAndIncreasingTime() {
        FumigationView f = newAeratingFumigation("GRV");
        sample(f.id(), "fg-grv-1", 1, "10.00", W0);

        ApiException dupSeq = assertThrows(ApiException.class, () ->
                sample(f.id(), "fg-grv-2", 1, "10.00", W0.plusSeconds(60)));
        assertEquals(422, dupSeq.getStatus().value());
        assertEquals("DUPLICATE_SEQ", dupSeq.getCode());

        ApiException sameTime = assertThrows(ApiException.class, () ->
                sample(f.id(), "fg-grv-3", 2, "10.00", W0));
        assertEquals(422, sameTime.getStatus().value());
        assertEquals("NON_INCREASING_TIME", sameTime.getCode());

        ApiException earlier = assertThrows(ApiException.class, () ->
                sample(f.id(), "fg-grv-4", 2, "10.00", W0.minusSeconds(60)));
        assertEquals(422, earlier.getStatus().value());
        assertEquals("NON_INCREASING_TIME", earlier.getCode());

        ApiException negative = assertThrows(ApiException.class, () ->
                sample(f.id(), "fg-grv-5", 2, "-0.01", W0.plusSeconds(60)));
        assertEquals(400, negative.getStatus().value());
        assertEquals("INVALID_CONCENTRATION", negative.getCode());

        ApiException badSeq = assertThrows(ApiException.class, () ->
                sample(f.id(), "fg-grv-6", 0, "10.00", W0.plusSeconds(60)));
        assertEquals(400, badSeq.getStatus().value());
        assertEquals("INVALID_SEQ", badSeq.getCode());

        // 失败均不占键、不落数
        assertEquals(1, countRows("gas_reading"));
        sample(f.id(), "fg-grv-5", 2, "10.00", W0.plusSeconds(60));
        assertEquals(2, countRows("gas_reading"));
    }

    @Test
    void gasReadingRejectedBeforeAerating() {
        SiloView silo = newSilo("S-GST");
        FumigationView f = createFumigation(silo.id(), "fm-gst", W0, W0.plusSeconds(3600));

        ApiException ex = assertThrows(ApiException.class, () ->
                sample(f.id(), "fg-gst-1", 1, "10.00", W0));
        assertEquals(422, ex.getStatus().value());
        assertEquals("INVALID_STATE", ex.getCode());
    }

    @Test
    void gasReadingIdempotentReplayAndParamConflict() {
        FumigationView f = newAeratingFumigation("GID");
        GasReadingResponse first = sample(f.id(), "fg-gid-1", 1, "10.00", W0);
        assertEquals("RECORDED", first.result());
        assertFalse(first.replayed());

        GasReadingResponse replayed = sample(f.id(), "fg-gid-1", 1, "10.00", W0);
        assertTrue(replayed.replayed());
        assertEquals(first.id(), replayed.id());
        assertEquals(1, countRows("gas_reading"));

        ApiException conflict = assertThrows(ApiException.class, () ->
                sample(f.id(), "fg-gid-1", 2, "10.00", W0.plusSeconds(60)));
        assertEquals(409, conflict.getStatus().value());
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.getCode());
    }

    // ---------- 解除规则 ----------

    @Test
    void releaseAllowedWhenThreePointsSpanExactlyThirtyMinutesAtThreshold() {
        FumigationView f = newAeratingFumigation("REL");
        // 恰等阈值、首末恰等 30 分钟均合法
        sample(f.id(), "fg-rel-1", 1, "50.00", W0);
        sample(f.id(), "fg-rel-2", 2, "50.00", W0.plusSeconds(15 * 60));
        sample(f.id(), "fg-rel-3", 3, "50.00", W0.plusSeconds(30 * 60));

        fumigationService.confirmRelease(f.id(), new ReleaseConfirmRequest("fr-rel-1", 5, "复核人甲"));
        FumigationView released = fumigationService.confirmRelease(f.id(),
                new ReleaseConfirmRequest("fr-rel-2", 6, "复核人乙"));
        assertEquals(FumigationStatus.RELEASED, released.status());
    }

    @Test
    void releaseRejectedWhenSpanTooShort() {
        FumigationView f = newAeratingFumigation("RSP");
        sample(f.id(), "fg-rsp-1", 1, "10.00", W0);
        sample(f.id(), "fg-rsp-2", 2, "10.00", W0.plusSeconds(10 * 60));
        sample(f.id(), "fg-rsp-3", 3, "10.00", W0.plusSeconds(29 * 60));

        fumigationService.confirmRelease(f.id(), new ReleaseConfirmRequest("fr-rsp-1", 5, "复核人甲"));
        ApiException ex = assertThrows(ApiException.class, () ->
                fumigationService.confirmRelease(f.id(), new ReleaseConfirmRequest("fr-rsp-2", 6, "复核人乙")));
        assertEquals(422, ex.getStatus().value());
        assertEquals("RELEASE_NOT_SAFE", ex.getCode());
        assertEquals(FumigationStatus.AERATING, fumigationService.getFumigation(f.id()).status());
    }

    @Test
    void releaseRejectedWhenHighReadingBreaksStreak() {
        FumigationView f = newAeratingFumigation("RHI");
        sample(f.id(), "fg-rhi-1", 1, "10.00", W0);
        sample(f.id(), "fg-rhi-2", 2, "10.00", W0.plusSeconds(20 * 60));
        sample(f.id(), "fg-rhi-3", 3, "10.00", W0.plusSeconds(40 * 60));
        // 越限打断连续计数
        sample(f.id(), "fg-rhi-4", 4, "50.01", W0.plusSeconds(50 * 60));

        fumigationService.confirmRelease(f.id(), new ReleaseConfirmRequest("fr-rhi-1", 5, "复核人甲"));
        ApiException ex = assertThrows(ApiException.class, () ->
                fumigationService.confirmRelease(f.id(), new ReleaseConfirmRequest("fr-rhi-2", 6, "复核人乙")));
        assertEquals(422, ex.getStatus().value());
        assertEquals("RELEASE_NOT_SAFE", ex.getCode());

        // 补足越限后的连续三点且间隔≥30分钟才可解除
        sample(f.id(), "fg-rhi-5", 5, "10.00", W0.plusSeconds(60 * 60));
        sample(f.id(), "fg-rhi-6", 6, "10.00", W0.plusSeconds(70 * 60));
        sample(f.id(), "fg-rhi-7", 7, "10.00", W0.plusSeconds(90 * 60));
        FumigationView released = fumigationService.confirmRelease(f.id(),
                new ReleaseConfirmRequest("fr-rhi-2", 6, "复核人乙"));
        assertEquals(FumigationStatus.RELEASED, released.status());
    }

    @Test
    void releaseRejectedWhenSeqGapBreaksStreak() {
        FumigationView f = newAeratingFumigation("RGP");
        sample(f.id(), "fg-rgp-1", 1, "10.00", W0);
        sample(f.id(), "fg-rgp-2", 2, "10.00", W0.plusSeconds(20 * 60));
        // 缺号（序号 3 缺失）不视为安全
        sample(f.id(), "fg-rgp-4", 4, "10.00", W0.plusSeconds(40 * 60));

        fumigationService.confirmRelease(f.id(), new ReleaseConfirmRequest("fr-rgp-1", 5, "复核人甲"));
        ApiException ex = assertThrows(ApiException.class, () ->
                fumigationService.confirmRelease(f.id(), new ReleaseConfirmRequest("fr-rgp-2", 6, "复核人乙")));
        assertEquals(422, ex.getStatus().value());
        assertEquals("RELEASE_NOT_SAFE", ex.getCode());
    }

    @Test
    void releaseRequiresTwoDistinctReviewers() {
        FumigationView f = newAeratingFumigation("RVR");
        sample(f.id(), "fg-rvr-1", 1, "10.00", W0);
        sample(f.id(), "fg-rvr-2", 2, "10.00", W0.plusSeconds(20 * 60));
        sample(f.id(), "fg-rvr-3", 3, "10.00", W0.plusSeconds(40 * 60));

        fumigationService.confirmRelease(f.id(), new ReleaseConfirmRequest("fr-rvr-1", 5, "复核人甲"));
        ApiException same = assertThrows(ApiException.class, () ->
                fumigationService.confirmRelease(f.id(), new ReleaseConfirmRequest("fr-rvr-2", 6, "复核人甲")));
        assertEquals(422, same.getStatus().value());
        assertEquals("SAME_REVIEWER", same.getCode());

        // 失败不占键：换复核人后同键可成功
        FumigationView released = fumigationService.confirmRelease(f.id(),
                new ReleaseConfirmRequest("fr-rvr-2", 6, "复核人乙"));
        assertEquals(FumigationStatus.RELEASED, released.status());
    }

    @Test
    void releaseVersionConflict() {
        FumigationView f = newAeratingFumigation("RVC");
        ApiException ex = assertThrows(ApiException.class, () ->
                fumigationService.confirmRelease(f.id(), new ReleaseConfirmRequest("fr-rvc-1", 99, "复核人甲")));
        assertEquals(409, ex.getStatus().value());
        assertEquals("VERSION_CONFLICT", ex.getCode());
    }

    // ---------- 解除后迟到读数 ----------

    @Test
    void gasReadingAfterReleaseIsLateAndDoesNotChangeFrozenEvidence() {
        FumigationView f = newAeratingFumigation("LAT");
        sample(f.id(), "fg-lat-1", 1, "10.00", W0);
        sample(f.id(), "fg-lat-2", 2, "10.00", W0.plusSeconds(20 * 60));
        sample(f.id(), "fg-lat-3", 3, "10.00", W0.plusSeconds(40 * 60));
        fumigationService.confirmRelease(f.id(), new ReleaseConfirmRequest("fr-lat-1", 5, "复核人甲"));
        FumigationView released = fumigationService.confirmRelease(f.id(),
                new ReleaseConfirmRequest("fr-lat-2", 6, "复核人乙"));
        assertEquals(FumigationStatus.RELEASED, released.status());

        GasReadingResponse late = sample(f.id(), "fg-lat-4", 4, "99.00", W0.plusSeconds(50 * 60));
        assertEquals("LATE_RECORDED", late.result());
        assertFalse(late.replayed());
        // 不改已冻结证据：读数不落入 gas_reading，只记迟到事件
        assertEquals(3, countRows("gas_reading"));
        assertEquals(1, countRows("late_event"));
        assertEquals(3, fumigationService.gasReadings(f.id()).size());

        GasReadingResponse replayed = sample(f.id(), "fg-lat-4", 4, "99.00", W0.plusSeconds(50 * 60));
        assertEquals("LATE_RECORDED", replayed.result());
        assertTrue(replayed.replayed());
        assertEquals(1, countRows("late_event"));
    }

    // ---------- 查询 ----------

    @Test
    void queryFumigationAndReadings() {
        FumigationView f = newAeratingFumigation("QRY");
        sample(f.id(), "fg-qry-1", 1, "10.00", W0);
        sample(f.id(), "fg-qry-2", 2, "20.00", W0.plusSeconds(600));

        FumigationView view = fumigationService.getFumigation(f.id());
        assertEquals(FumigationStatus.AERATING, view.status());
        assertEquals(5, view.version());
        assertEquals(2, fumigationService.gasReadings(f.id()).size());
        assertEquals(1, fumigationService.listBySilo(view.siloId()).size());

        ApiException missing = assertThrows(ApiException.class, () -> fumigationService.getFumigation(999999L));
        assertEquals(404, missing.getStatus().value());
        assertEquals("FUMIGATION_NOT_FOUND", missing.getCode());
    }

    // ---------- 测试辅助 ----------

    private FumigationView createFumigation(long siloId, String requestId, Instant start, Instant end) {
        return fumigationService.create(siloId, new CreateFumigationRequest(requestId,
                start, end, THRESHOLD, List.of("密封粮堆", "投放合成药剂", "密闭熏蒸")));
    }

    private void approveTwice(long fumigationId, String tag) {
        fumigationService.approve(fumigationId, new ApproveRequest("fa-" + tag + "-1", 1, "审批人甲"));
        fumigationService.approve(fumigationId, new ApproveRequest("fa-" + tag + "-2", 2, "审批人乙"));
    }

    /** 构造已转散气（AERATING，版本 5）的熏蒸单，绑定一台空闲风机。 */
    private FumigationView newAeratingFumigation(String tag) {
        SiloView silo = newSilo("S-" + tag);
        FanView fan = newFan("F-" + tag);
        FumigationView f = createFumigation(silo.id(), "fm-" + tag.toLowerCase(),
                W0, W0.plusSeconds(7200));
        approveTwice(f.id(), tag.toLowerCase());
        fumigationService.start(f.id(), new StartFumigationRequest("fs-" + tag.toLowerCase(), 3));
        return fumigationService.finish(f.id(),
                new FinishFumigationRequest("ff-" + tag.toLowerCase(), 4, fan.id()));
    }

    private GasReadingResponse sample(long fumigationId, String requestId, int seq,
                                      String concentration, Instant measuredAt) {
        return fumigationService.sample(fumigationId, new GasReadingRequest(requestId, seq,
                new BigDecimal(concentration), measuredAt));
    }
}
