package com.example.starter;

import java.math.BigDecimal;

import com.example.starter.dto.GrainDtos.CreateJobRequest;
import com.example.starter.dto.GrainDtos.FanView;
import com.example.starter.dto.GrainDtos.JobView;
import com.example.starter.dto.GrainDtos.RecheckRequest;
import com.example.starter.dto.GrainDtos.RecheckView;
import com.example.starter.dto.GrainDtos.SiloView;
import com.example.starter.error.ApiException;
import com.example.starter.model.JobStatus;
import com.example.starter.model.RecheckDecision;
import com.example.starter.model.SiloStatus;
import com.example.starter.model.VentilationPurpose;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通风作业测试：湿度边界、半开作业窗、两轮上限、风机唯一占用与锁定原子性、幂等与归档终态。
 */
class VentilationJobTest extends AbstractIntegrationTest {

    @Test
    void humidityEqualToEquilibriumIsAllowed() {
        SiloView silo = newSilo("S-HE");
        FanView fan = newFan("F-HE");
        raiseAlarm(silo.id(), "r-he-alarm");

        JobView job = ventilationService.createJob(silo.id(), new CreateJobRequest(
                "j-he-1", fan.id(), VentilationPurpose.COOLING,
                T0.plusHours(2), T0.plusHours(3), new BigDecimal("70.00")));

        assertEquals(JobStatus.EXECUTING, job.status());
        assertEquals(1, job.roundNo());
        assertEquals(SiloStatus.VENTILATING, siloService.getSilo(silo.id()).status());
        assertEquals("LOCKED", siloService.getFan(fan.id()).status());
    }

    @Test
    void humidityAboveEquilibriumIsForbidden() {
        SiloView silo = newSilo("S-HF");
        FanView fan = newFan("F-HF");
        raiseAlarm(silo.id(), "r-hf-alarm");

        ApiException ex = assertThrows(ApiException.class, () -> ventilationService.createJob(silo.id(),
                new CreateJobRequest("j-hf-1", fan.id(), VentilationPurpose.DEHUMIDIFYING,
                        T0.plusHours(2), T0.plusHours(3), new BigDecimal("70.01"))));

        assertEquals(422, ex.getStatus().value());
        assertEquals("VENTILATION_FORBIDDEN_HUMIDITY", ex.getCode());
        assertEquals(0, countRows("ventilation_job"));
        assertEquals(SiloStatus.ALARMING, siloService.getSilo(silo.id()).status());
    }

    @Test
    void halfOpenWindowAdjacentIsAllowedButOverlapConflicts() {
        SiloView silo = newSilo("S-WIN");
        FanView fan = newFan("F-WIN");
        raiseAlarm(silo.id(), "r-win-alarm");
        JobView first = ventilationService.createJob(silo.id(), newJobRequest("j-win-1", fan.id()));
        ventilationService.completeJob(first.id());
        ventilationService.recheck(first.id(), new RecheckRequest("rc-win-1",
                new BigDecimal("6.00"), new BigDecimal("5.00"), T0.plusHours(4)));

        // 与既有 [10:00, 11:00) 重叠的 [10:30, 11:30) 冲突
        ApiException ex = assertThrows(ApiException.class, () -> ventilationService.createJob(silo.id(),
                new CreateJobRequest("j-win-2", fan.id(), VentilationPurpose.COOLING,
                        T0.plusHours(2).plusMinutes(30), T0.plusHours(3).plusMinutes(30),
                        new BigDecimal("65.00"))));
        assertEquals(409, ex.getStatus().value());
        assertEquals("WINDOW_CONFLICT", ex.getCode());

        // 端点相接 [11:00, 12:00) 与 [10:00, 11:00) 不冲突
        JobView adjacent = ventilationService.createJob(silo.id(), new CreateJobRequest(
                "j-win-3", fan.id(), VentilationPurpose.COOLING,
                T0.plusHours(3), T0.plusHours(4), new BigDecimal("65.00")));
        assertEquals(2, adjacent.roundNo());
        assertEquals(2, countRows("ventilation_job"));
    }

    @Test
    void twoRoundsExceededThenManualHandlingAndNoThirdJob() {
        SiloView silo = newSilo("S-2R");
        FanView fan = newFan("F-2R");
        raiseAlarm(silo.id(), "r-2r-alarm");

        // 第一轮：复测梯度仍超标 → 允许二次通风
        JobView round1 = ventilationService.createJob(silo.id(), newJobRequest("j-2r-1", fan.id()));
        ventilationService.completeJob(round1.id());
        RecheckView recheck1 = ventilationService.recheck(round1.id(), new RecheckRequest(
                "rc-2r-1", new BigDecimal("6.00"), new BigDecimal("5.00"), T0.plusHours(4)));
        assertTrue(recheck1.exceeded());
        assertEquals(RecheckDecision.SECOND_VENTILATION, recheck1.decision());
        assertEquals(SiloStatus.ALARMING, siloService.getSilo(silo.id()).status());

        // 第二轮：复测仍超标 → 转人工处置
        JobView round2 = ventilationService.createJob(silo.id(), new CreateJobRequest(
                "j-2r-2", fan.id(), VentilationPurpose.COOLING,
                T0.plusHours(5), T0.plusHours(6), new BigDecimal("65.00")));
        assertEquals(2, round2.roundNo());
        ventilationService.completeJob(round2.id());
        RecheckView recheck2 = ventilationService.recheck(round2.id(), new RecheckRequest(
                "rc-2r-2", new BigDecimal("7.00"), new BigDecimal("5.00"), T0.plusHours(7)));
        assertEquals(RecheckDecision.MANUAL_HANDLING, recheck2.decision());
        assertEquals(SiloStatus.MANUAL_HANDLING, siloService.getSilo(silo.id()).status());

        // 第三轮被拒绝
        ApiException ex = assertThrows(ApiException.class, () -> ventilationService.createJob(silo.id(),
                new CreateJobRequest("j-2r-3", fan.id(), VentilationPurpose.COOLING,
                        T0.plusHours(8), T0.plusHours(9), new BigDecimal("65.00"))));
        assertEquals(409, ex.getStatus().value());
        assertEquals("MANUAL_HANDLING_REQUIRED", ex.getCode());
        assertEquals(2, countRows("ventilation_job"));

        // 两轮复测对比可复算
        var rechecks = ventilationService.rechecksOfSilo(silo.id());
        assertEquals(2, rechecks.size());
        assertEquals(1, rechecks.get(0).roundNo());
        assertEquals(2, rechecks.get(1).roundNo());
        assertTrue(rechecks.get(0).exceeded());
        assertTrue(rechecks.get(1).exceeded());
    }

    @Test
    void gradientEqualToLimitPasses() {
        SiloView silo = newSilo("S-GE");
        FanView fan = newFan("F-GE");
        raiseAlarm(silo.id(), "r-ge-alarm");
        JobView job = ventilationService.createJob(silo.id(), newJobRequest("j-ge-1", fan.id()));
        ventilationService.completeJob(job.id());

        RecheckView recheck = ventilationService.recheck(job.id(), new RecheckRequest(
                "rc-ge-1", new BigDecimal("5.00"), new BigDecimal("5.00"), T0.plusHours(4)));

        assertFalse(recheck.exceeded());
        assertEquals(RecheckDecision.PASSED, recheck.decision());
    }

    @Test
    void fanServesOnlyOneSiloAtATime() {
        SiloView siloA = newSilo("S-FA");
        SiloView siloB = newSilo("S-FB");
        FanView fan = newFan("F-SHARED");
        raiseAlarm(siloA.id(), "r-fa-alarm");
        raiseAlarm(siloB.id(), "r-fb-alarm");
        ventilationService.createJob(siloA.id(), newJobRequest("j-fa-1", fan.id()));

        ApiException ex = assertThrows(ApiException.class, () -> ventilationService.createJob(siloB.id(),
                newJobRequest("j-fb-1", fan.id())));

        assertEquals(409, ex.getStatus().value());
        assertEquals("FAN_BUSY", ex.getCode());
    }

    @Test
    void jobStartAndFanLockAreAtomic() {
        SiloView siloA = newSilo("S-LA");
        SiloView siloB = newSilo("S-LB");
        FanView fan = newFan("F-LA");
        raiseAlarm(siloA.id(), "r-la-alarm");
        raiseAlarm(siloB.id(), "r-lb-alarm");
        ventilationService.createJob(siloA.id(), newJobRequest("j-la-1", fan.id()));

        // 风机锁定失败则整个事务回滚：不得作业开始而风机未锁
        assertThrows(ApiException.class, () -> ventilationService.createJob(siloB.id(),
                newJobRequest("j-lb-1", fan.id())));

        assertEquals(1, countRows("ventilation_job"));
        assertEquals(SiloStatus.ALARMING, siloService.getSilo(siloB.id()).status());
        assertEquals("LOCKED", siloService.getFan(fan.id()).status());
    }

    @Test
    void sameKeyReplayReturnsFirstJobAndDifferentParamsConflict() {
        SiloView silo = newSilo("S-IR");
        FanView fan = newFan("F-IR");
        raiseAlarm(silo.id(), "r-ir-alarm");
        CreateJobRequest request = newJobRequest("j-ir-1", fan.id());

        JobView first = ventilationService.createJob(silo.id(), request);
        JobView replay = ventilationService.createJob(silo.id(), request);

        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.id(), replay.id());
        assertEquals(1, countRows("ventilation_job"));

        // 同键异参：409 且不改变既有作业窗
        ApiException ex = assertThrows(ApiException.class, () -> ventilationService.createJob(silo.id(),
                new CreateJobRequest("j-ir-1", fan.id(), VentilationPurpose.COOLING,
                        T0.plusHours(5), T0.plusHours(6), new BigDecimal("65.00"))));
        assertEquals(409, ex.getStatus().value());
        assertEquals("IDEMPOTENCY_CONFLICT", ex.getCode());

        JobView stored = ventilationService.getJob(first.id());
        assertEquals(T0.plusHours(2), stored.windowStart());
        assertEquals(T0.plusHours(3), stored.windowEnd());
        assertEquals(1, countRows("ventilation_job"));
    }

    @Test
    void completeReleasesFanAndMovesToRechecking() {
        SiloView silo = newSilo("S-CP");
        FanView fan = newFan("F-CP");
        raiseAlarm(silo.id(), "r-cp-alarm");
        JobView job = ventilationService.createJob(silo.id(), newJobRequest("j-cp-1", fan.id()));

        JobView completed = ventilationService.completeJob(job.id());

        assertEquals(JobStatus.COMPLETED, completed.status());
        assertEquals("IDLE", siloService.getFan(fan.id()).status());
        assertEquals(SiloStatus.RECHECKING, siloService.getSilo(silo.id()).status());
    }

    @Test
    void archiveIsTerminalAndRepeatable() {
        SiloView silo = newSilo("S-AR");
        FanView fan = newFan("F-AR");
        raiseAlarm(silo.id(), "r-ar-alarm");
        JobView job = ventilationService.createJob(silo.id(), newJobRequest("j-ar-1", fan.id()));
        ventilationService.completeJob(job.id());
        ventilationService.recheck(job.id(), new RecheckRequest("rc-ar-1",
                new BigDecimal("4.00"), new BigDecimal("5.00"), T0.plusHours(4)));

        JobView archived = ventilationService.archive(job.id());
        assertEquals(JobStatus.ARCHIVED, archived.status());
        assertEquals(SiloStatus.ARCHIVED, siloService.getSilo(silo.id()).status());

        // 重复归档返回幂等成功
        JobView again = ventilationService.archive(job.id());
        assertEquals(JobStatus.ARCHIVED, again.status());
        assertTrue(again.replayed());
        assertEquals(1, countRows("ventilation_job"));

        // 归档后禁止再下达作业
        ApiException ex = assertThrows(ApiException.class, () -> ventilationService.createJob(silo.id(),
                newJobRequest("j-ar-2", fan.id())));
        assertEquals(409, ex.getStatus().value());
        assertEquals("SILO_ARCHIVED", ex.getCode());
    }

    @Test
    void archiveRequiresPassedRecheck() {
        SiloView silo = newSilo("S-AN");
        FanView fan = newFan("F-AN");
        raiseAlarm(silo.id(), "r-an-alarm");
        JobView job = ventilationService.createJob(silo.id(), newJobRequest("j-an-1", fan.id()));
        ventilationService.completeJob(job.id());

        ApiException ex = assertThrows(ApiException.class, () -> ventilationService.archive(job.id()));
        assertEquals(409, ex.getStatus().value());
        assertEquals("RECHECK_NOT_PASSED", ex.getCode());
        assertEquals(JobStatus.COMPLETED, ventilationService.getJob(job.id()).status());
    }

    @Test
    void recheckReplayAndConflict() {
        SiloView silo = newSilo("S-RR");
        FanView fan = newFan("F-RR");
        raiseAlarm(silo.id(), "r-rr-alarm");
        JobView job = ventilationService.createJob(silo.id(), newJobRequest("j-rr-1", fan.id()));
        ventilationService.completeJob(job.id());
        RecheckRequest request = new RecheckRequest("rc-rr-1",
                new BigDecimal("4.00"), new BigDecimal("5.00"), T0.plusHours(4));

        RecheckView first = ventilationService.recheck(job.id(), request);
        RecheckView replay = ventilationService.recheck(job.id(), request);
        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.id(), replay.id());
        assertEquals(1, countRows("recheck"));

        ApiException ex = assertThrows(ApiException.class, () -> ventilationService.recheck(job.id(),
                new RecheckRequest("rc-rr-1", new BigDecimal("9.00"),
                        new BigDecimal("5.00"), T0.plusHours(4))));
        assertEquals(409, ex.getStatus().value());
        assertEquals("IDEMPOTENCY_CONFLICT", ex.getCode());
        assertEquals(1, countRows("recheck"));
    }
}
