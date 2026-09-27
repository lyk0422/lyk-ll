package com.example.starter;

import java.math.BigDecimal;

import com.example.starter.dto.FumigationDtos.ApplyFumigationRequest;
import com.example.starter.dto.FumigationDtos.ApprovalView;
import com.example.starter.dto.FumigationDtos.ApproveRequest;
import com.example.starter.dto.FumigationDtos.ConfirmationView;
import com.example.starter.dto.FumigationDtos.FumigationDetailView;
import com.example.starter.dto.FumigationDtos.FumigationView;
import com.example.starter.dto.FumigationDtos.GasReadingRequest;
import com.example.starter.dto.FumigationDtos.GasReadingView;
import com.example.starter.dto.FumigationDtos.ReleaseConfirmRequest;
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
 * 熏蒸闭环主流程：申请→双人批准→开始锁仓→结束转散气→三点达标→双人复核解除；
 * 版本从 1 开始逐次加 1，方案版本冻结，仓锁与风机原子释放。
 */
class FumigationFlowTest extends FumigationTestSupport {

    @Test
    void fullFlowLocksAndReleasesAtomically() {
        SiloView silo = newSilo("S-FLOW");
        FanView fan = newFan("F-FLOW");

        // 申请：版本 1，待审批
        FumigationView order = newOrder(silo.id(), "flow");
        assertEquals(FumigationStatus.PENDING_APPROVAL, order.status());
        assertEquals(1, order.version());
        assertFalse(order.replayed());

        // 两名不同审批人批准：版本 1→2→3，状态转已批准
        ApprovalView first = fumigationService.approve(order.id(),
                new ApproveRequest("f-flow-ap1", "审批人甲", 1));
        assertEquals(2, first.version());
        assertEquals(FumigationStatus.PENDING_APPROVAL, first.status());
        assertEquals(1, first.approvals());
        ApprovalView second = fumigationService.approve(order.id(),
                new ApproveRequest("f-flow-ap2", "审批人乙", 2));
        assertEquals(3, second.version());
        assertEquals(FumigationStatus.APPROVED, second.status());

        // 开始：原子锁仓并冻结方案版本（冻结为批准时的版本 3）
        FumigationView started = fumigationService.start(order.id(),
                new StartRequest("f-flow-start", 3, F0.plusHours(2)));
        assertEquals(FumigationStatus.FUMIGATING, started.status());
        assertEquals(4, started.version());
        assertEquals(3, started.frozenPlanVersion());
        assertEquals(F0.plusHours(2), started.startedAt());
        assertEquals(SiloStatus.FUMIGATING, siloService.getSilo(silo.id()).status());

        // 锁仓期间禁止普通通风
        ApiException blocked = assertThrows(ApiException.class,
                () -> ventilationService.createJob(silo.id(), newJobRequest("j-flow-blocked", fan.id())));
        assertEquals(409, blocked.getStatus().value());

        // 结束转散气：绑定空闲风机
        FumigationView aerating = fumigationService.finish(order.id(),
                new com.example.starter.dto.FumigationDtos.FinishRequest("f-flow-fin", 4, fan.id(),
                        F0.plusHours(3)));
        assertEquals(FumigationStatus.AERATING, aerating.status());
        assertEquals(5, aerating.version());
        assertEquals(fan.id(), aerating.fanId());
        FanView locked = siloService.getFan(fan.id());
        assertEquals("LOCKED", locked.status());
        Long lockedFumigation = jdbc.queryForObject(
                "SELECT locked_fumigation_id FROM fan WHERE id = ?", Long.class, fan.id());
        assertEquals(order.id(), lockedFumigation);
        assertEquals(SiloStatus.AERATING, siloService.getSilo(silo.id()).status());

        // 三点达标（含恰等阈值、恰满 30 分钟间隔）
        sampleThreeGoodPoints(order.id(), "flow");
        FumigationDetailView detail = fumigationService.getOrder(order.id());
        assertTrue(detail.assessment().releasable(), "恰等阈值且恰满30分钟应判可解除");
        assertEquals(1, detail.assessment().runStartSeq());
        assertEquals(3, detail.assessment().runEndSeq());

        // 两名不同复核人确认：第一次不释放，第二次原子释放仓锁与风机
        ConfirmationView confirm1 = fumigationService.confirmRelease(order.id(),
                new ReleaseConfirmRequest("f-flow-cf1", "复核人丙", 5));
        assertFalse(confirm1.released());
        assertEquals(6, confirm1.version());
        assertEquals(SiloStatus.AERATING, siloService.getSilo(silo.id()).status());
        assertEquals("LOCKED", siloService.getFan(fan.id()).status());

        ConfirmationView confirm2 = fumigationService.confirmRelease(order.id(),
                new ReleaseConfirmRequest("f-flow-cf2", "复核人丁", 6));
        assertTrue(confirm2.released());
        assertEquals(7, confirm2.version());

        FumigationView released = fumigationService.getOrder(order.id()).order();
        assertEquals(FumigationStatus.RELEASED, released.status());
        assertEquals(7, released.version());
        assertEquals(SiloStatus.MONITORING, siloService.getSilo(silo.id()).status());
        FanView freed = siloService.getFan(fan.id());
        assertEquals("IDLE", freed.status());
        assertNull(freed.lockedJobId());
        Long freedFumigation = jdbc.queryForObject(
                "SELECT locked_fumigation_id FROM fan WHERE id = ?", Long.class, fan.id());
        assertNull(freedFumigation);

        // 关闭后读数只记迟到，不改已冻结证据
        GasReadingView late = fumigationService.sample(order.id(), new GasReadingRequest(
                "f-flow-late", 4, new BigDecimal("9.999"), F0.plusHours(12)));
        assertEquals("LATE_RECORDED", late.result());
        assertFalse(late.replayed());
        assertEquals(3, countRows("gas_reading"), "迟到读数不得写入已关闭单据");
        assertEquals(1, countRows("late_event"));
        GasReadingView lateReplay = fumigationService.sample(order.id(), new GasReadingRequest(
                "f-flow-late", 4, new BigDecimal("9.999"), F0.plusHours(12)));
        assertTrue(lateReplay.replayed());
        assertEquals(1, countRows("late_event"));
    }

    @Test
    void applyIsIdempotentAndRejectsConflictingParams() {
        SiloView silo = newSilo("S-IDEM");
        ApplyFumigationRequest request = new ApplyFumigationRequest("f-idem-apply",
                F0.plusHours(2), F0.plusHours(4), "合成方案", new BigDecimal("0.500"));

        FumigationView created = fumigationService.apply(silo.id(), request);
        FumigationView replayed = fumigationService.apply(silo.id(), request);
        assertTrue(replayed.replayed());
        assertEquals(created.id(), replayed.id());
        assertEquals(1, countRows("fumigation_order"));

        // 同键异参（含合法文本中的分隔符差异）判 409
        ApiException conflict = assertThrows(ApiException.class,
                () -> fumigationService.apply(silo.id(), new ApplyFumigationRequest("f-idem-apply",
                        F0.plusHours(2), F0.plusHours(4), "合成方案|改", new BigDecimal("0.500"))));
        assertEquals(409, conflict.getStatus().value());
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.getCode());
        assertEquals(1, countRows("fumigation_order"));
    }

    @Test
    void startAndFinishReplayFrozenResult() {
        SiloView silo = newSilo("S-REPLAY");
        FanView fan = newFan("F-REPLAY");
        FumigationView approved = newApprovedOrder(silo.id(), "replay");

        FumigationView started = fumigationService.start(approved.id(),
                new StartRequest("f-replay-start", 3, F0.plusHours(2)));
        FumigationView startedReplay = fumigationService.start(approved.id(),
                new StartRequest("f-replay-start", 3, F0.plusHours(2)));
        assertTrue(startedReplay.replayed());
        assertEquals(started.id(), startedReplay.id());
        assertEquals(4, startedReplay.version());

        // 同键异参判 409
        ApiException conflict = assertThrows(ApiException.class,
                () -> fumigationService.start(approved.id(),
                        new StartRequest("f-replay-start", 3, F0.plusHours(2).plusMinutes(1))));
        assertEquals(409, conflict.getStatus().value());

        FumigationView finished = fumigationService.finish(approved.id(),
                new com.example.starter.dto.FumigationDtos.FinishRequest("f-replay-fin", 4, fan.id(),
                        F0.plusHours(3)));
        FumigationView finishedReplay = fumigationService.finish(approved.id(),
                new com.example.starter.dto.FumigationDtos.FinishRequest("f-replay-fin", 4, fan.id(),
                        F0.plusHours(3)));
        assertTrue(finishedReplay.replayed());
        assertEquals(finished.version(), finishedReplay.version());
        assertEquals(1, countRows("fumigation_order"));
    }

    @Test
    void approvalAndConfirmationReplayFrozenResult() {
        SiloView silo = newSilo("S-AREPLAY");
        FanView fan = newFan("F-AREPLAY");
        FumigationView order = newOrder(silo.id(), "areplay");

        ApprovalView first = fumigationService.approve(order.id(),
                new ApproveRequest("f-areplay-ap1", "审批人甲", 1));
        ApprovalView firstReplay = fumigationService.approve(order.id(),
                new ApproveRequest("f-areplay-ap1", "审批人甲", 1));
        assertTrue(firstReplay.replayed());
        assertEquals(first.version(), firstReplay.version());
        assertEquals(1, countRows("fumigation_approval"));

        ApiException conflict = assertThrows(ApiException.class,
                () -> fumigationService.approve(order.id(),
                        new ApproveRequest("f-areplay-ap1", "审批人甲", 9)));
        assertEquals(409, conflict.getStatus().value());

        approveTwiceRestAndRelease(silo.id(), fan.id(), order.id());
    }

    /** 继续完成审批、开始、散气与解除，验证确认重放。 */
    private void approveTwiceRestAndRelease(long siloId, long fanId, long orderId) {
        fumigationService.approve(orderId, new ApproveRequest("f-areplay-ap2", "审批人乙", 2));
        fumigationService.start(orderId, new StartRequest("f-areplay-start", 3, F0.plusHours(2)));
        fumigationService.finish(orderId,
                new com.example.starter.dto.FumigationDtos.FinishRequest("f-areplay-fin", 4, fanId,
                        F0.plusHours(3)));
        sampleThreeGoodPoints(orderId, "areplay");

        ConfirmationView confirm1 = fumigationService.confirmRelease(orderId,
                new ReleaseConfirmRequest("f-areplay-cf1", "复核人丙", 5));
        ConfirmationView confirm1Replay = fumigationService.confirmRelease(orderId,
                new ReleaseConfirmRequest("f-areplay-cf1", "复核人丙", 5));
        assertTrue(confirm1Replay.replayed());
        assertEquals(confirm1.version(), confirm1Replay.version());
        assertFalse(confirm1Replay.released());
        assertEquals(1, countRows("fumigation_confirmation"));

        ConfirmationView confirm2 = fumigationService.confirmRelease(orderId,
                new ReleaseConfirmRequest("f-areplay-cf2", "复核人丁", 6));
        assertTrue(confirm2.released());
        ConfirmationView confirm2Replay = fumigationService.confirmRelease(orderId,
                new ReleaseConfirmRequest("f-areplay-cf2", "复核人丁", 6));
        assertTrue(confirm2Replay.replayed());
        assertTrue(confirm2Replay.released());
        assertEquals(2, countRows("fumigation_confirmation"));
    }
}
