package com.example.starter;

import java.math.BigDecimal;
import java.time.Instant;

import com.example.starter.dto.FumigationDtos.ApproveRequest;
import com.example.starter.dto.FumigationDtos.CreateFumigationRequest;
import com.example.starter.dto.FumigationDtos.FinishRequest;
import com.example.starter.dto.FumigationDtos.FumigationView;
import com.example.starter.dto.FumigationDtos.ReleaseRequest;
import com.example.starter.dto.FumigationDtos.SampleRequest;
import com.example.starter.dto.FumigationDtos.SampleView;
import com.example.starter.dto.FumigationDtos.StartRequest;
import com.example.starter.dto.GrainDtos.FanView;
import com.example.starter.dto.GrainDtos.SiloView;
import com.example.starter.error.ApiException;
import com.example.starter.model.FumigationStatus;
import com.example.starter.model.SiloStatus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 熏蒸审批与散气解除联锁测试：主流程、状态机失败分支、三点解除规则（恰等合法、
 * 越限打断、缺号不安全、首末间隔）、版本冲突、幂等重放与迟到读数。
 */
class FumigationFlowTest extends AbstractIntegrationTest {

    @Test
    void fullFlowLocksSiloAndFanThenReleases() {
        SiloView silo = newSilo("S-FM1");
        FanView fan = newFan("F-FM1");

        FumigationView created = newFumigation(silo.id(), "fm-create-1");
        assertEquals(FumigationStatus.PENDING_APPROVAL, created.status());
        assertEquals(1, created.version());
        assertFalse(created.replayed());

        FumigationView afterFirst = approveNow(created.id(), "fm-ap-1", "approver-a");
        assertEquals(FumigationStatus.PENDING_APPROVAL, afterFirst.status());
        assertEquals(2, afterFirst.version());
        assertEquals(1, afterFirst.approvers().size());

        FumigationView approved = approveNow(created.id(), "fm-ap-2", "approver-b");
        assertEquals(FumigationStatus.APPROVED, approved.status());
        assertEquals(3, approved.version());
        assertEquals(2, approved.approvers().size());

        FumigationView started = fumigationService.start(created.id(),
                new StartRequest("fm-st-1", 3, I0.plusSeconds(2 * 3600)));
        assertEquals(FumigationStatus.FUMIGATING, started.status());
        assertEquals(4, started.version());
        assertEquals(3, started.frozenPlanVersion());
        assertEquals(SiloStatus.FUMIGATING, siloService.getSilo(silo.id()).status());

        FumigationView aerating = fumigationService.finish(created.id(),
                new FinishRequest("fm-fi-1", 4, fan.id(), I0.plusSeconds(3 * 3600)));
        assertEquals(FumigationStatus.AERATING, aerating.status());
        assertEquals(5, aerating.version());
        assertEquals(fan.id(), aerating.fanId());
        assertEquals(SiloStatus.AERATING, siloService.getSilo(silo.id()).status());
        assertEquals("LOCKED", siloService.getFan(fan.id()).status());
        assertEquals(created.id(), siloService.getFan(fan.id()).lockedFumigationId());

        // 越限读数打断连续计数；随后三点恰等阈值、首末恰等30分钟均合法
        sample(created.id(), "fm-s-1", 1, "0.6000", I0.plusSeconds(3 * 3600 + 600));
        sample(created.id(), "fm-s-2", 2, "0.5000", I0.plusSeconds(3 * 3600 + 1200));
        sample(created.id(), "fm-s-3", 3, "0.5000", I0.plusSeconds(3 * 3600 + 1800));
        sample(created.id(), "fm-s-4", 4, "0.4000", I0.plusSeconds(3 * 3600 + 3000));

        FumigationView released = fumigationService.release(created.id(),
                new ReleaseRequest("fm-rl-1", 5, "reviewer-a", "reviewer-b", I0.plusSeconds(4 * 3600)));
        assertEquals(FumigationStatus.RELEASED, released.status());
        assertEquals(6, released.version());
        assertEquals(SiloStatus.MONITORING, siloService.getSilo(silo.id()).status());
        assertEquals("IDLE", siloService.getFan(fan.id()).status());
        assertNull(siloService.getFan(fan.id()).lockedFumigationId());
    }

    @Test
    void createRejectsInvalidWindowAndMissingSilo() {
        SiloView silo = newSilo("S-FM2");
        ApiException badWindow = assertThrows(ApiException.class, () -> fumigationService.create(
                silo.id(), new CreateFumigationRequest("fm-bad-w", I0.plusSeconds(7200),
                        I0.plusSeconds(7200), new BigDecimal("0.5"))));
        assertEquals(400, badWindow.getStatus().value());
        assertEquals("INVALID_WINDOW", badWindow.getCode());

        ApiException missing = assertThrows(ApiException.class, () -> fumigationService.create(
                999999L, new CreateFumigationRequest("fm-no-silo", I0, I0.plusSeconds(3600),
                        new BigDecimal("0.5"))));
        assertEquals(404, missing.getStatus().value());
        assertEquals(0, countRows("fumigation_order"));
    }

    @Test
    void fumigationWindowMutexWithVentilationHalfOpen() {
        SiloView silo = newSilo("S-FM3");
        FanView fan = newFan("F-FM3");
        raiseAlarm(silo.id(), "r-fm3-alarm");
        // 普通通风窗 10:00-11:00（Asia/Shanghai）= UTC 02:00-03:00
        long jobId = ventilationService.createJob(silo.id(), newJobRequest("j-fm3", fan.id())).id();

        // 通风执行中粮仓被锁定，申请熏蒸 → 422
        ApiException stateForbidden = assertThrows(ApiException.class, () -> fumigationService.create(
                silo.id(), new CreateFumigationRequest("fm-busy", I0.plusSeconds(14400),
                        I0.plusSeconds(18000), new BigDecimal("0.5"))));
        assertEquals(422, stateForbidden.getStatus().value());
        assertEquals("SILO_STATE_FORBIDDEN", stateForbidden.getCode());

        // 通风完成、复测超标转二次通风（粮仓回到告警态），通风窗占用仍保留
        ventilationService.completeJob(jobId);
        ventilationService.recheck(jobId, new com.example.starter.dto.GrainDtos.RecheckRequest(
                "rc-fm3", new BigDecimal("5.00"), new BigDecimal("1.00"), T0.plusHours(4)));
        assertEquals(SiloStatus.ALARMING, siloService.getSilo(silo.id()).status());

        // 重叠：UTC 02:30-03:30 与通风窗相交 → 422
        ApiException overlap = assertThrows(ApiException.class, () -> fumigationService.create(
                silo.id(), new CreateFumigationRequest("fm-ov", I0.plusSeconds(9000),
                        I0.plusSeconds(12600), new BigDecimal("0.5"))));
        assertEquals(422, overlap.getStatus().value());
        assertEquals("WINDOW_CONFLICT", overlap.getCode());

        // 端点相接：UTC 03:00-04:00 与通风窗 [02:00,03:00) 相接不冲突
        FumigationView touching = fumigationService.create(silo.id(),
                new CreateFumigationRequest("fm-touch", I0.plusSeconds(10800),
                        I0.plusSeconds(14400), new BigDecimal("0.5")));
        assertEquals(FumigationStatus.PENDING_APPROVAL, touching.status());

        // 与既有未解除熏蒸窗重叠 → 422；相接可共存
        ApiException fumOverlap = assertThrows(ApiException.class, () -> fumigationService.create(
                silo.id(), new CreateFumigationRequest("fm-ov2", I0.plusSeconds(12000),
                        I0.plusSeconds(15000), new BigDecimal("0.5"))));
        assertEquals("WINDOW_CONFLICT", fumOverlap.getCode());
        FumigationView adjacent = fumigationService.create(silo.id(),
                new CreateFumigationRequest("fm-adj", I0.plusSeconds(14400),
                        I0.plusSeconds(18000), new BigDecimal("0.5")));
        assertEquals(FumigationStatus.PENDING_APPROVAL, adjacent.status());
    }

    @Test
    void approveRequiresTwoDistinctApproversAndMatchingVersion() {
        SiloView silo = newSilo("S-FM4");
        FumigationView created = newFumigation(silo.id(), "fm-ap-distinct");

        ApiException versionConflict = assertThrows(ApiException.class, () ->
                fumigationService.approve(created.id(),
                        new ApproveRequest("fm-ap-v", 5, "approver-a", I0)));
        assertEquals(409, versionConflict.getStatus().value());
        assertEquals("VERSION_CONFLICT", versionConflict.getCode());

        // 失败不占键：同 requestId 修正版本后可成功
        FumigationView first = fumigationService.approve(created.id(),
                new ApproveRequest("fm-ap-v", 1, "approver-a", I0));
        assertEquals(2, first.version());

        ApiException duplicated = assertThrows(ApiException.class, () ->
                fumigationService.approve(created.id(),
                        new ApproveRequest("fm-ap-dup", 2, "approver-a", I0)));
        assertEquals(422, duplicated.getStatus().value());
        assertEquals("APPROVER_DUPLICATED", duplicated.getCode());

        ApiException missing = assertThrows(ApiException.class, () ->
                fumigationService.approve(999999L,
                        new ApproveRequest("fm-ap-404", 1, "approver-c", I0)));
        assertEquals(404, missing.getStatus().value());
    }

    @Test
    void startRequiresApprovalAndNoExecutingVentilation() {
        SiloView silo = newSilo("S-FM5");
        FumigationView created = newFumigation(silo.id(), "fm-st-req");

        ApiException notApproved = assertThrows(ApiException.class, () ->
                fumigationService.start(created.id(), new StartRequest("fm-st-early", 1, I0)));
        assertEquals(422, notApproved.getStatus().value());
        assertEquals("INVALID_FUMIGATION_STATE", notApproved.getCode());

        approveNow(created.id(), "fm-st-a1", "approver-a");
        approveNow(created.id(), "fm-st-a2", "approver-b");

        // 存在执行中通风时禁止开始
        SiloView other = newSilo("S-FM5B");
        FanView fan = newFan("F-FM5");
        raiseAlarm(other.id(), "r-fm5-alarm");
        FumigationView second = newFumigation(other.id(), "fm-st-busy");
        approveNow(second.id(), "fm-st-b1", "approver-a");
        approveNow(second.id(), "fm-st-b2", "approver-b");
        ventilationService.createJob(other.id(), newJobRequest("j-fm5", fan.id()));
        ApiException executing = assertThrows(ApiException.class, () ->
                fumigationService.start(second.id(), new StartRequest("fm-st-blocked", 3, I0)));
        assertEquals(422, executing.getStatus().value());
        assertEquals("VENTILATION_EXECUTING", executing.getCode());

        // 正常开始后锁仓：普通通风被禁止（旧入口拒绝）
        FumigationView started = fumigationService.start(created.id(),
                new StartRequest("fm-st-ok", 3, I0.plusSeconds(7200)));
        assertEquals(FumigationStatus.FUMIGATING, started.status());
        ApiException ventilationForbidden = assertThrows(ApiException.class, () ->
                ventilationService.createJob(silo.id(), newJobRequest("j-fm5-blocked", fan.id())));
        assertEquals(409, ventilationForbidden.getStatus().value());
    }

    @Test
    void finishBindsIdleFanAtomically() {
        SiloView silo = newSilo("S-FM6");
        SiloView other = newSilo("S-FM6B");
        FanView fan = newFan("F-FM6");
        FumigationView first = newFumigation(silo.id(), "fm-fi-a");
        FumigationView second = newFumigation(other.id(), "fm-fi-b");
        toAerating(first.id(), fan.id());

        // 第二个熏蒸单走完批准与开始，但结束转散气时风机已被占用 → 422 且不进入散气
        approveNow(second.id(), "fm-fi-b1", "approver-a");
        approveNow(second.id(), "fm-fi-b2", "approver-b");
        fumigationService.start(second.id(), new StartRequest("fm-fi-bs", 3, I0.plusSeconds(7200)));
        ApiException fanBusy = assertThrows(ApiException.class, () ->
                fumigationService.finish(second.id(),
                        new FinishRequest("fm-fi-bf", 4, fan.id(), I0.plusSeconds(10800))));
        assertEquals(422, fanBusy.getStatus().value());
        assertEquals("FAN_BUSY", fanBusy.getCode());
        assertEquals(FumigationStatus.FUMIGATING, fumigationService.getFumigation(second.id()).status());
        assertEquals(SiloStatus.FUMIGATING, siloService.getSilo(other.id()).status());

        ApiException fanMissing = assertThrows(ApiException.class, () ->
                fumigationService.finish(second.id(),
                        new FinishRequest("fm-fi-bf2", 4, 999999L, I0.plusSeconds(10800))));
        assertEquals(404, fanMissing.getStatus().value());
    }

    @Test
    void sampleValidatesConcentrationSeqAndIncreasingTime() {
        SiloView silo = newSilo("S-FM7");
        FanView fan = newFan("F-FM7");
        FumigationView created = newFumigation(silo.id(), "fm-smp");
        toAerating(created.id(), fan.id());

        ApiException negative = assertThrows(ApiException.class, () ->
                fumigationService.sample(created.id(), new SampleRequest("fm-s-neg", 1,
                        new BigDecimal("-0.1"), I0.plusSeconds(11000))));
        assertEquals(400, negative.getStatus().value());

        sample(created.id(), "fm-s-ok1", 1, "0.4", I0.plusSeconds(11000));

        ApiException sameTime = assertThrows(ApiException.class, () ->
                fumigationService.sample(created.id(), new SampleRequest("fm-s-eq", 2,
                        new BigDecimal("0.4"), I0.plusSeconds(11000))));
        assertEquals(422, sameTime.getStatus().value());
        assertEquals("READING_TIME_NOT_INCREASING", sameTime.getCode());

        ApiException seqConflict = assertThrows(ApiException.class, () ->
                fumigationService.sample(created.id(), new SampleRequest("fm-s-dup", 1,
                        new BigDecimal("0.3"), I0.plusSeconds(11100))));
        assertEquals(409, seqConflict.getStatus().value());
        assertEquals("READING_SEQ_CONFLICT", seqConflict.getCode());
        assertEquals(1, countRows("fumigation_reading"));
    }

    @Test
    void sampleRejectedBeforeAerating() {
        SiloView silo = newSilo("S-FM8");
        FumigationView created = newFumigation(silo.id(), "fm-smp-early");
        ApiException ex = assertThrows(ApiException.class, () ->
                fumigationService.sample(created.id(), new SampleRequest("fm-s-e1", 1,
                        new BigDecimal("0.4"), I0.plusSeconds(11000))));
        assertEquals(422, ex.getStatus().value());
        assertEquals("INVALID_FUMIGATION_STATE", ex.getCode());
    }

    @Test
    void releaseRequiresThreeConsecutiveSafePointsWithSpan() {
        SiloView silo = newSilo("S-FM9");
        FanView fan = newFan("F-FM9");
        FumigationView created = newFumigation(silo.id(), "fm-rl-rules");
        toAerating(created.id(), fan.id());

        // 不足三点 → 422
        sample(created.id(), "fm-r-1", 1, "0.4", I0.plusSeconds(11000));
        ApiException insufficient = assertThrows(ApiException.class, () -> releaseNow(created.id(), "fm-rl-i"));
        assertEquals(422, insufficient.getStatus().value());
        assertEquals("INSUFFICIENT_READINGS", insufficient.getCode());

        // 缺号（1、2、4）不视为安全 → 422
        sample(created.id(), "fm-r-2", 2, "0.4", I0.plusSeconds(11600));
        sample(created.id(), "fm-r-4", 4, "0.4", I0.plusSeconds(12200));
        ApiException gap = assertThrows(ApiException.class, () -> releaseNow(created.id(), "fm-rl-g"));
        assertEquals("READING_SEQ_GAP", gap.getCode());

        // 补齐序号但首末间隔不足30分钟 → 422
        sample(created.id(), "fm-r-3", 3, "0.4", I0.plusSeconds(12800));
        sample(created.id(), "fm-r-5", 5, "0.4", I0.plusSeconds(13000));
        ApiException span = assertThrows(ApiException.class, () -> releaseNow(created.id(), "fm-rl-s"));
        assertEquals("SPAN_TOO_SHORT", span.getCode());

        // 越限读数打断连续计数 → 422
        sample(created.id(), "fm-r-6", 6, "0.6", I0.plusSeconds(14000));
        sample(created.id(), "fm-r-7", 7, "0.4", I0.plusSeconds(14600));
        sample(created.id(), "fm-r-8", 8, "0.4", I0.plusSeconds(15200));
        ApiException exceeded = assertThrows(ApiException.class, () -> releaseNow(created.id(), "fm-rl-x"));
        assertEquals("CONCENTRATION_ABOVE_THRESHOLD", exceeded.getCode());

        // 相同复核人 → 422
        ApiException sameReviewer = assertThrows(ApiException.class, () ->
                fumigationService.release(created.id(), new ReleaseRequest("fm-rl-dup", 5,
                        "reviewer-a", "reviewer-a", I0.plusSeconds(16000))));
        assertEquals(422, sameReviewer.getStatus().value());
        assertEquals("REVIEWER_DUPLICATED", sameReviewer.getCode());

        // 连续三点（6越限前的旧点不参与）：7、8、9 安全且首末≥30分钟 → 解除成功
        sample(created.id(), "fm-r-9", 9, "0.4", I0.plusSeconds(16400));
        FumigationView released = releaseNow(created.id(), "fm-rl-ok");
        assertEquals(FumigationStatus.RELEASED, released.status());
    }

    @Test
    void releaseChecksVersionAndFailureKeepsLock() {
        SiloView silo = newSilo("S-FM10");
        FanView fan = newFan("F-FM10");
        FumigationView created = newFumigation(silo.id(), "fm-rl-ver");
        toAerating(created.id(), fan.id());
        sample(created.id(), "fm-v-1", 1, "0.4", I0.plusSeconds(11000));
        sample(created.id(), "fm-v-2", 2, "0.4", I0.plusSeconds(11900));
        sample(created.id(), "fm-v-3", 3, "0.9", I0.plusSeconds(12800));

        ApiException versionConflict = assertThrows(ApiException.class, () ->
                fumigationService.release(created.id(), new ReleaseRequest("fm-rl-v", 99,
                        "reviewer-a", "reviewer-b", I0.plusSeconds(14000))));
        assertEquals(409, versionConflict.getStatus().value());

        // 失败一项均不释放：越限读数导致解除拒绝，仓锁与风机保持
        ApiException exceeded = assertThrows(ApiException.class, () ->
                fumigationService.release(created.id(), new ReleaseRequest("fm-rl-f", 5,
                        "reviewer-a", "reviewer-b", I0.plusSeconds(14000))));
        assertEquals(422, exceeded.getStatus().value());
        assertEquals("CONCENTRATION_ABOVE_THRESHOLD", exceeded.getCode());
        assertEquals(SiloStatus.AERATING, siloService.getSilo(silo.id()).status());
        assertEquals("LOCKED", siloService.getFan(fan.id()).status());
        assertEquals(FumigationStatus.AERATING, fumigationService.getFumigation(created.id()).status());
    }

    @Test
    void lateSampleAfterReleaseOnlyRecordsEvent() {
        SiloView silo = newSilo("S-FM11");
        FanView fan = newFan("F-FM11");
        FumigationView created = newFumigation(silo.id(), "fm-late");
        toAerating(created.id(), fan.id());
        sample(created.id(), "fm-l-1", 1, "0.4", I0.plusSeconds(11000));
        sample(created.id(), "fm-l-2", 2, "0.4", I0.plusSeconds(11900));
        sample(created.id(), "fm-l-3", 3, "0.4", I0.plusSeconds(12800));
        releaseNow(created.id(), "fm-l-rl");

        SampleView late = fumigationService.sample(created.id(), new SampleRequest("fm-l-late", 4,
                new BigDecimal("0.9"), I0.plusSeconds(14000)));
        assertTrue(late.late());
        assertNull(late.id());
        // 已冻结证据不变：读数仍三条，迟到只记事件
        assertEquals(3, countRows("fumigation_reading"));
        assertEquals(1, countRows("late_event"));
        assertEquals(3, fumigationService.readings(created.id()).size());

        // 同键重放迟到结果
        SampleView replayed = fumigationService.sample(created.id(), new SampleRequest("fm-l-late", 4,
                new BigDecimal("0.9"), I0.plusSeconds(14000)));
        assertTrue(replayed.replayed());
        assertTrue(replayed.late());
        assertEquals(1, countRows("late_event"));
    }

    @Test
    void idempotentReplayAndConflict() {
        SiloView silo = newSilo("S-FM12");
        FumigationView created = newFumigation(silo.id(), "fm-idem-c");

        FumigationView replayed = newFumigation(silo.id(), "fm-idem-c");
        assertTrue(replayed.replayed());
        assertEquals(created.id(), replayed.id());
        assertEquals(1, countRows("fumigation_order"));

        ApiException conflict = assertThrows(ApiException.class, () -> fumigationService.create(
                silo.id(), new CreateFumigationRequest("fm-idem-c", I0.plusSeconds(7200),
                        I0.plusSeconds(14000), new BigDecimal("0.5"))));
        assertEquals(409, conflict.getStatus().value());
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.getCode());

        // 批准重放：同键同参返回首次冻结结果（版本停留在首次）
        ApproveRequest approve = new ApproveRequest("fm-idem-a", 1, "approver-a", I0);
        FumigationView first = fumigationService.approve(created.id(), approve);
        FumigationView second = fumigationService.approve(created.id(), approve);
        assertTrue(second.replayed());
        assertEquals(first.version(), second.version());
        assertEquals(1, countRows("fumigation_approval"));

        // 同键异参（精确时刻不同）→ 409
        ApiException approveConflict = assertThrows(ApiException.class, () ->
                fumigationService.approve(created.id(),
                        new ApproveRequest("fm-idem-a", 1, "approver-a", I0.plusSeconds(600))));
        assertEquals(409, approveConflict.getStatus().value());
    }

    @Test
    void sampleIdempotentReplayAndConflict() {
        SiloView silo = newSilo("S-FM13");
        FanView fan = newFan("F-FM13");
        FumigationView created = newFumigation(silo.id(), "fm-idem-s");
        toAerating(created.id(), fan.id());

        SampleView first = fumigationService.sample(created.id(), new SampleRequest("fm-is-1", 1,
                new BigDecimal("0.4"), I0.plusSeconds(11000)));
        SampleView replayed = fumigationService.sample(created.id(), new SampleRequest("fm-is-1", 1,
                new BigDecimal("0.4"), I0.plusSeconds(11000)));
        assertTrue(replayed.replayed());
        assertEquals(first.id(), replayed.id());
        assertEquals(1, countRows("fumigation_reading"));

        ApiException conflict = assertThrows(ApiException.class, () ->
                fumigationService.sample(created.id(), new SampleRequest("fm-is-1", 1,
                        new BigDecimal("0.45"), I0.plusSeconds(11000))));
        assertEquals(409, conflict.getStatus().value());
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.getCode());
    }

    @Test
    void releaseIdempotentReplayReturnsFrozenResult() {
        SiloView silo = newSilo("S-FM14");
        FanView fan = newFan("F-FM14");
        FumigationView created = newFumigation(silo.id(), "fm-idem-r");
        toAerating(created.id(), fan.id());
        sample(created.id(), "fm-ir-1", 1, "0.4", I0.plusSeconds(11000));
        sample(created.id(), "fm-ir-2", 2, "0.4", I0.plusSeconds(11900));
        sample(created.id(), "fm-ir-3", 3, "0.4", I0.plusSeconds(12800));

        ReleaseRequest release = new ReleaseRequest("fm-rl-idem", 5, "reviewer-a", "reviewer-b",
                I0.plusSeconds(5 * 3600));
        FumigationView first = fumigationService.release(created.id(), release);
        FumigationView replayed = fumigationService.release(created.id(), release);
        assertTrue(replayed.replayed());
        assertEquals(first.version(), replayed.version());
        assertEquals(FumigationStatus.RELEASED, replayed.status());
        assertEquals(6, fumigationService.getFumigation(created.id()).version());
    }

    private FumigationView releaseNow(long fumigationId, String requestId) {
        int version = fumigationService.getFumigation(fumigationId).version();
        return fumigationService.release(fumigationId, new ReleaseRequest(requestId, version,
                "reviewer-a", "reviewer-b", I0.plusSeconds(5 * 3600)));
    }
}
