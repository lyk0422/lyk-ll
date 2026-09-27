package com.example.starter;

import java.math.BigDecimal;

import com.example.starter.dto.FumigationDtos.ApplyFumigationRequest;
import com.example.starter.dto.FumigationDtos.ApproveRequest;
import com.example.starter.dto.FumigationDtos.ConfirmationView;
import com.example.starter.dto.FumigationDtos.FinishRequest;
import com.example.starter.dto.FumigationDtos.FumigationView;
import com.example.starter.dto.FumigationDtos.GasReadingRequest;
import com.example.starter.dto.FumigationDtos.ReleaseConfirmRequest;
import com.example.starter.dto.FumigationDtos.StartRequest;
import com.example.starter.dto.GrainDtos.CreateJobRequest;
import com.example.starter.dto.GrainDtos.FanView;
import com.example.starter.dto.GrainDtos.SiloView;
import com.example.starter.error.ApiException;
import com.example.starter.model.FumigationStatus;
import com.example.starter.model.SiloStatus;
import com.example.starter.model.VentilationPurpose;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 熏蒸失败分支：参数非法 400、对象不存在 404、业务拒绝 422、同键异参 409；
 * 窗口半开互斥、越限打断、缺号不安全、失败不占幂等键。
 */
class FumigationFailureTest extends FumigationTestSupport {

    @Test
    void invalidParamsReturn400() {
        SiloView silo = newSilo("S-BAD");
        ApiException window = assertThrows(ApiException.class,
                () -> fumigationService.apply(silo.id(), new ApplyFumigationRequest("f-bad-w",
                        F0.plusHours(4), F0.plusHours(2), "合成方案", new BigDecimal("0.500"))));
        assertEquals(400, window.getStatus().value());
        assertEquals("INVALID_WINDOW", window.getCode());

        ApiException threshold = assertThrows(ApiException.class,
                () -> fumigationService.apply(silo.id(), new ApplyFumigationRequest("f-bad-t",
                        F0.plusHours(2), F0.plusHours(4), "合成方案", new BigDecimal("-0.1"))));
        assertEquals(400, threshold.getStatus().value());
        assertEquals("INVALID_THRESHOLD", threshold.getCode());

        FumigationView aerating = newAeratingOrder(silo.id(), newFan("F-BAD").id(), "bad");
        ApiException concentration = assertThrows(ApiException.class,
                () -> fumigationService.sample(aerating.id(), new GasReadingRequest("f-bad-c", 1,
                        new BigDecimal("-0.001"), F0.plusHours(10))));
        assertEquals(400, concentration.getStatus().value());
        assertEquals("INVALID_CONCENTRATION", concentration.getCode());
        assertEquals(0, countRows("fumigation_order") - 1, "失败请求不得留下额外单据");
    }

    @Test
    void missingObjectsReturn404() {
        ApiException siloMissing = assertThrows(ApiException.class,
                () -> fumigationService.apply(999999L, new ApplyFumigationRequest("f-404-s",
                        F0.plusHours(2), F0.plusHours(4), "合成方案", new BigDecimal("0.500"))));
        assertEquals(404, siloMissing.getStatus().value());
        assertEquals("SILO_NOT_FOUND", siloMissing.getCode());

        ApiException orderMissing = assertThrows(ApiException.class,
                () -> fumigationService.approve(999999L, new ApproveRequest("f-404-o", "审批人甲", 1)));
        assertEquals(404, orderMissing.getStatus().value());
        assertEquals("FUMIGATION_NOT_FOUND", orderMissing.getCode());

        ApiException getMissing = assertThrows(ApiException.class,
                () -> fumigationService.getOrder(999999L));
        assertEquals(404, getMissing.getStatus().value());

        SiloView silo = newSilo("S-404F");
        FumigationView fumigating = newFumigatingOrder(silo.id(), "404f");
        ApiException fanMissing = assertThrows(ApiException.class,
                () -> fumigationService.finish(fumigating.id(),
                        new FinishRequest("f-404f-fin", 4, 999999L, F0.plusHours(3))));
        assertEquals(404, fanMissing.getStatus().value());
        assertEquals("FAN_NOT_FOUND", fanMissing.getCode());
    }

    @Test
    void sameApproverAndStaleVersionRejected() {
        SiloView silo = newSilo("S-APPR");
        FumigationView order = newOrder(silo.id(), "appr");
        fumigationService.approve(order.id(), new ApproveRequest("f-appr-ap1", "审批人甲", 1));

        ApiException same = assertThrows(ApiException.class,
                () -> fumigationService.approve(order.id(),
                        new ApproveRequest("f-appr-ap2", "审批人甲", 2)));
        assertEquals(422, same.getStatus().value());
        assertEquals("SAME_APPROVER", same.getCode());

        ApiException stale = assertThrows(ApiException.class,
                () -> fumigationService.approve(order.id(),
                        new ApproveRequest("f-appr-ap3", "审批人乙", 1)));
        assertEquals(422, stale.getStatus().value());
        assertEquals("VERSION_CONFLICT", stale.getCode());
        assertEquals(1, countRows("fumigation_approval"), "失败审批不占键不留痕");

        // 正确版本继续推进不受影响
        fumigationService.approve(order.id(), new ApproveRequest("f-appr-ap4", "审批人乙", 2));
        assertEquals(FumigationStatus.APPROVED, fumigationService.getOrder(order.id()).order().status());
    }

    @Test
    void invalidStateTransitionsRejected() {
        SiloView silo = newSilo("S-STATE");
        FanView fan = newFan("F-STATE");
        FumigationView order = newOrder(silo.id(), "state");

        ApiException startEarly = assertThrows(ApiException.class,
                () -> fumigationService.start(order.id(), new StartRequest("f-state-s", 1,
                        F0.plusHours(2))));
        assertEquals(422, startEarly.getStatus().value());
        assertEquals("INVALID_ORDER_STATE", startEarly.getCode());

        ApiException sampleEarly = assertThrows(ApiException.class,
                () -> fumigationService.sample(order.id(), new GasReadingRequest("f-state-g", 1,
                        new BigDecimal("0.1"), F0.plusHours(10))));
        assertEquals(422, sampleEarly.getStatus().value());

        ApiException finishEarly = assertThrows(ApiException.class,
                () -> fumigationService.finish(order.id(),
                        new FinishRequest("f-state-f", 1, fan.id(), F0.plusHours(3))));
        assertEquals(422, finishEarly.getStatus().value());

        ApiException confirmEarly = assertThrows(ApiException.class,
                () -> fumigationService.confirmRelease(order.id(),
                        new ReleaseConfirmRequest("f-state-c", "复核人丙", 1)));
        assertEquals(422, confirmEarly.getStatus().value());
        assertEquals(FumigationStatus.PENDING_APPROVAL,
                fumigationService.getOrder(order.id()).order().status());
    }

    @Test
    void windowMutexWithVentilationHalfOpen() {
        SiloView silo = newSilo("S-MUTEX");
        FanView fan = newFan("F-MUTEX");
        raiseAlarm(silo.id(), "r-mutex-alarm");
        // 通风窗 [10:00, 11:00)
        ventilationService.createJob(silo.id(), newJobRequest("j-mutex", fan.id()));

        // 重叠 [10:30, 12:00) → 422
        ApiException overlap = assertThrows(ApiException.class,
                () -> fumigationService.apply(silo.id(), new ApplyFumigationRequest("f-mutex-o",
                        T0.plusHours(2).plusMinutes(30), T0.plusHours(4), "合成方案",
                        new BigDecimal("0.500"))));
        assertEquals(422, overlap.getStatus().value());
        assertEquals("WINDOW_CONFLICT", overlap.getCode());

        // 端点相接 [11:00, 12:00) 可共存
        FumigationView touching = fumigationService.apply(silo.id(), new ApplyFumigationRequest(
                "f-mutex-t", T0.plusHours(3), T0.plusHours(4), "合成方案", new BigDecimal("0.500")));
        assertEquals(FumigationStatus.PENDING_APPROVAL, touching.status());
    }

    @Test
    void ventilationWindowBlockedByFumigation() {
        SiloView silo = newSilo("S-VMUTEX");
        FanView fan = newFan("F-VMUTEX");
        raiseAlarm(silo.id(), "r-vmutex-alarm");
        // 熏蒸窗 [10:00, 12:00)
        fumigationService.apply(silo.id(), new ApplyFumigationRequest("f-vmutex",
                T0.plusHours(2), T0.plusHours(4), "合成方案", new BigDecimal("0.500")));

        // 通风窗 [11:00, 13:00) 与熏蒸窗重叠 → 409
        ApiException overlap = assertThrows(ApiException.class,
                () -> ventilationService.createJob(silo.id(), new CreateJobRequest("j-vmutex-o",
                        fan.id(), VentilationPurpose.COOLING, T0.plusHours(3), T0.plusHours(5),
                        new BigDecimal("65.00"))));
        assertEquals(409, overlap.getStatus().value());
        assertEquals("WINDOW_CONFLICT", overlap.getCode());

        // 端点相接 [12:00, 13:00) 可共存
        ventilationService.createJob(silo.id(), new CreateJobRequest("j-vmutex-t", fan.id(),
                VentilationPurpose.COOLING, T0.plusHours(4), T0.plusHours(5),
                new BigDecimal("65.00")));
        assertEquals(1, countRows("ventilation_job"));
    }

    @Test
    void startRechecksExecutingVentilation() {
        SiloView silo = newSilo("S-EXEC");
        FanView fan = newFan("F-EXEC");
        raiseAlarm(silo.id(), "r-exec-alarm");
        // 通风执行中（窗 [10:00, 11:00)），熏蒸窗 [12:00, 14:00) 不重叠，申请可受理
        ventilationService.createJob(silo.id(), newJobRequest("j-exec", fan.id()));
        FumigationView approved = fumigationService.apply(silo.id(), new ApplyFumigationRequest(
                "f-exec", T0.plusHours(4), T0.plusHours(6), "合成方案", new BigDecimal("0.500")));
        fumigationService.approve(approved.id(), new ApproveRequest("f-exec-ap1", "审批人甲", 1));
        fumigationService.approve(approved.id(), new ApproveRequest("f-exec-ap2", "审批人乙", 2));

        // 实际开始时再查：存在执行中通风 → 422
        ApiException executing = assertThrows(ApiException.class,
                () -> fumigationService.start(approved.id(),
                        new StartRequest("f-exec-s", 3, T0.plusHours(4))));
        assertEquals(422, executing.getStatus().value());
        assertEquals("VENTILATION_EXECUTING", executing.getCode());
        assertEquals(FumigationStatus.APPROVED,
                fumigationService.getOrder(approved.id()).order().status());
    }

    @Test
    void readingSequenceAndTimeRulesEnforced() {
        SiloView silo = newSilo("S-SEQ");
        FumigationView aerating = newAeratingOrder(silo.id(), newFan("F-SEQ").id(), "seq");
        sample(aerating.id(), "seq", 1, "0.400", F0.plusHours(10));

        // 时刻不递增 → 422
        ApiException time = assertThrows(ApiException.class,
                () -> fumigationService.sample(aerating.id(), new GasReadingRequest("f-seq-g2", 2,
                        new BigDecimal("0.300"), F0.plusHours(10))));
        assertEquals(422, time.getStatus().value());
        assertEquals("READING_TIME_NOT_INCREASING", time.getCode());

        // 序号重复 → 422
        ApiException seq = assertThrows(ApiException.class,
                () -> fumigationService.sample(aerating.id(), new GasReadingRequest("f-seq-g3", 1,
                        new BigDecimal("0.300"), F0.plusHours(11))));
        assertEquals(422, seq.getStatus().value());
        assertEquals("SEQ_CONFLICT", seq.getCode());
        assertEquals(1, countRows("gas_reading"), "失败采样不占键不留痕");
    }

    @Test
    void gapSequenceIsNotSafeUntilFilled() {
        SiloView silo = newSilo("S-GAP");
        FumigationView aerating = newAeratingOrder(silo.id(), newFan("F-GAP").id(), "gap");
        // 缺号：1、2、4（时刻递增；2→4 首末恰满 30 分钟）
        sample(aerating.id(), "gap", 1, "0.400", F0.plusHours(10));
        sample(aerating.id(), "gap", 2, "0.400", F0.plusHours(10).plusMinutes(5));
        sample(aerating.id(), "gap", 4, "0.400", F0.plusHours(10).plusMinutes(35));

        assertFalse(fumigationService.getOrder(aerating.id()).assessment().releasable(),
                "缺号不视为安全");
        fumigationService.confirmRelease(aerating.id(),
                new ReleaseConfirmRequest("f-gap-cf1", "复核人丙", 5));
        ApiException notMet = assertThrows(ApiException.class,
                () -> fumigationService.confirmRelease(aerating.id(),
                        new ReleaseConfirmRequest("f-gap-cf2", "复核人丁", 6)));
        assertEquals(422, notMet.getStatus().value());
        assertEquals("RELEASE_CONDITION_NOT_MET", notMet.getCode());

        // 失败不占键：补录缺号（时刻继续递增）后同键同参可成功
        sample(aerating.id(), "gap", 3, "0.400", F0.plusHours(10).plusMinutes(50));
        assertTrue(fumigationService.getOrder(aerating.id()).assessment().releasable());
        ConfirmationView released = fumigationService.confirmRelease(aerating.id(),
                new ReleaseConfirmRequest("f-gap-cf2", "复核人丁", 6));
        assertTrue(released.released());
    }

    @Test
    void overLimitBreaksConsecutiveCount() {
        SiloView silo = newSilo("S-OVER");
        FumigationView aerating = newAeratingOrder(silo.id(), newFan("F-OVER").id(), "over");
        sample(aerating.id(), "over", 1, "0.400", F0.plusHours(10));
        sample(aerating.id(), "over", 2, "0.400", F0.plusHours(10).plusMinutes(15));
        // 越限打断连续计数
        sample(aerating.id(), "over", 3, "0.600", F0.plusHours(10).plusMinutes(30));
        assertFalse(fumigationService.getOrder(aerating.id()).assessment().releasable());

        // 重新累计三点达标（首末 30 分钟）
        sample(aerating.id(), "over", 4, "0.400", F0.plusHours(10).plusMinutes(45));
        sample(aerating.id(), "over", 5, "0.400", F0.plusHours(10).plusMinutes(60));
        sample(aerating.id(), "over", 6, "0.400", F0.plusHours(10).plusMinutes(75));
        assertTrue(fumigationService.getOrder(aerating.id()).assessment().releasable());
    }

    @Test
    void spanLessThanThirtyMinutesIsNotReleasable() {
        SiloView silo = newSilo("S-SPAN");
        FumigationView aerating = newAeratingOrder(silo.id(), newFan("F-SPAN").id(), "span");
        sample(aerating.id(), "span", 1, "0.400", F0.plusHours(10));
        sample(aerating.id(), "span", 2, "0.400", F0.plusHours(10).plusMinutes(5));
        sample(aerating.id(), "span", 3, "0.400", F0.plusHours(10).plusMinutes(20));
        assertFalse(fumigationService.getOrder(aerating.id()).assessment().releasable(),
                "首末间隔不足30分钟不得解除");

        // 第四点使最新三点首末间隔恰满 30 分钟 → 可解除
        sample(aerating.id(), "span", 4, "0.400", F0.plusHours(10).plusMinutes(35));
        assertTrue(fumigationService.getOrder(aerating.id()).assessment().releasable());
    }

    @Test
    void sameReviewerRejectedAndFailureKeepsState() {
        SiloView silo = newSilo("S-REV");
        FanView fan = newFan("F-REV");
        FumigationView aerating = newAeratingOrder(silo.id(), fan.id(), "rev");
        sampleThreeGoodPoints(aerating.id(), "rev");

        fumigationService.confirmRelease(aerating.id(),
                new ReleaseConfirmRequest("f-rev-cf1", "复核人丙", 5));
        ApiException same = assertThrows(ApiException.class,
                () -> fumigationService.confirmRelease(aerating.id(),
                        new ReleaseConfirmRequest("f-rev-cf2", "复核人丙", 6)));
        assertEquals(422, same.getStatus().value());
        assertEquals("SAME_REVIEWER", same.getCode());

        ApiException stale = assertThrows(ApiException.class,
                () -> fumigationService.confirmRelease(aerating.id(),
                        new ReleaseConfirmRequest("f-rev-cf3", "复核人丁", 5)));
        assertEquals(422, stale.getStatus().value());
        assertEquals("VERSION_CONFLICT", stale.getCode());

        // 失败项均不释放：仓锁与风机保持占用，版本不变
        FumigationView order = fumigationService.getOrder(aerating.id()).order();
        assertEquals(FumigationStatus.AERATING, order.status());
        assertEquals(6, order.version());
        assertEquals(SiloStatus.AERATING, siloService.getSilo(silo.id()).status());
        assertEquals("LOCKED", siloService.getFan(fan.id()).status());
        assertEquals(1, countRows("fumigation_confirmation"));
    }

    @Test
    void failedReleaseDoesNotOccupyKeyAndKeepsLock() {
        SiloView silo = newSilo("S-FAIL");
        FanView fan = newFan("F-FAIL");
        FumigationView aerating = newAeratingOrder(silo.id(), fan.id(), "fail");
        // 仅两点达标，第三点缺失 → 解除必失败
        sample(aerating.id(), "fail", 1, "0.400", F0.plusHours(10));
        sample(aerating.id(), "fail", 2, "0.400", F0.plusHours(10).plusMinutes(15));

        fumigationService.confirmRelease(aerating.id(),
                new ReleaseConfirmRequest("f-fail-cf1", "复核人丙", 5));
        ApiException notMet = assertThrows(ApiException.class,
                () -> fumigationService.confirmRelease(aerating.id(),
                        new ReleaseConfirmRequest("f-fail-cf2", "复核人丁", 6)));
        assertEquals("RELEASE_CONDITION_NOT_MET", notMet.getCode());

        // 失败整体回滚：确认记录、版本、仓锁、风机均不变
        assertEquals(1, countRows("fumigation_confirmation"));
        assertEquals(6, fumigationService.getOrder(aerating.id()).order().version());
        assertEquals(SiloStatus.AERATING, siloService.getSilo(silo.id()).status());
        assertEquals("LOCKED", siloService.getFan(fan.id()).status());

        // 条件满足后同键同参重试成功（失败不占键）
        sample(aerating.id(), "fail", 3, "0.400", F0.plusHours(10).plusMinutes(30));
        ConfirmationView released = fumigationService.confirmRelease(aerating.id(),
                new ReleaseConfirmRequest("f-fail-cf2", "复核人丁", 6));
        assertTrue(released.released());
        assertEquals(SiloStatus.MONITORING, siloService.getSilo(silo.id()).status());
        assertEquals("IDLE", siloService.getFan(fan.id()).status());
    }
}
