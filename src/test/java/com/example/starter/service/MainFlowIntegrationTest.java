package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.example.starter.AbstractDivingIntegrationTest;
import com.example.starter.api.CloseRequest;
import com.example.starter.api.CloseResponse;
import com.example.starter.api.ExitRequest;
import com.example.starter.api.PlanResponse;
import com.example.starter.api.SegmentResponse;
import com.example.starter.api.VitalsRequest;
import com.example.starter.service.DivingService.ServiceResult;

/**
 * 主流程：申请（恰免减压极限）→ 两人成对水下记录（下潜恰 18m/min）→ 出水 → 恰满 2 小时观察 → 结案，
 * 并验证同键重放幂等、异参 409、结案终态与迟到体征。
 */
class MainFlowIntegrationTest extends AbstractDivingIntegrationTest {

    @Test
    void fullFlowAtNdlLimitWithExactRateAndExactObservation() {
        // 1) 申请：18 米档 NDL 恰为 56 分钟，计划 56 分钟恰在极限，合法。
        ServiceResult<PlanResponse> applied = service.apply(
                application("app-1", "diver-a", "diver-b", 18, 56));
        assertThat(applied.httpStatus()).isEqualTo(201);
        PlanResponse plan = applied.body();
        assertThat(plan.ndlMinutes()).isEqualTo(56);
        assertThat(plan.atLimit()).isTrue();
        assertThat(plan.planVersion()).isEqualTo(1);
        String opNo = plan.operationNo();

        // 同键同参重放：返回首次结果。
        ServiceResult<PlanResponse> replay = service.apply(
                application("app-1", "diver-a", "diver-b", 18, 56));
        assertThat(replay.httpStatus()).isEqualTo(201);
        assertThat(replay.replay()).isTrue();
        assertThat(replay.body().operationNo()).isEqualTo(opNo);
        assertThat(count("dive_operation", "")).isEqualTo(1L);

        // 同键异参：409，且潜次状态不变（深度仍 18、版本仍 1）。
        expectBusiness(() -> service.apply(
                application("app-1", "diver-a", "diver-b", 20, 40)), "IDEMPOTENT_FINGERPRINT_MISMATCH");
        PlanResponse unchanged = service.apply(
                application("app-1", "diver-a", "diver-b", 18, 56)).body();
        assertThat(unchanged.depthM()).isEqualTo(18);
        assertThat(unchanged.planVersion()).isEqualTo(1);

        // 2) 下潜记录：18 米 / 60 秒 = 恰 18m/min。先单人提交不生效。
        SegmentResponse first = service.recordSegment(
                descendSegment("seg-d-1", opNo, "diver-a", 0, 18, 0, 60)).body();
        assertThat(first.effective()).isFalse();
        assertThat(first.pairState()).isEqualTo("PENDING");
        assertThat(first.rateMPerMin()).isEqualByComparingTo("18.0000");
        assertThat(first.rateAlarm()).isFalse();
        // 单人记录不入台账。
        assertThat(count("diver_time_ledger", "")).isZero();

        // 搭档同槽提交，成对生效。
        SegmentResponse second = service.recordSegment(
                descendSegment("seg-d-2", opNo, "diver-b", 0, 18, 0, 60)).body();
        assertThat(second.effective()).isTrue();
        assertThat(second.pairState()).isEqualTo("EFFECTIVE");
        assertThat(second.ledgerSeconds24h()).isEqualTo(60L);
        assertThat(count("diver_time_ledger", "")).isEqualTo(2L);

        // 3) 水底记录：56 分钟恰 NDL（3360 秒），两人成对。
        service.recordSegment(bottomSegment("seg-b-1", opNo, "diver-a", 18, 60, 3420));
        SegmentResponse bottomB = service.recordSegment(
                bottomSegment("seg-b-2", opNo, "diver-b", 18, 60, 3420)).body();
        assertThat(bottomB.effective()).isTrue();
        // 下潜 60 秒 + 水底 3360 秒 = 3420 秒（不足 6 小时上限）。
        assertThat(bottomB.ledgerSeconds24h()).isEqualTo(3420L);

        // 4) 出水：时刻 3420，进入观察期，期满 = 3420 + 7200。
        var exit = service.exit(new ExitRequest("exit-1", opNo, 3420L)).body();
        assertThat(exit.status()).isEqualTo("OBSERVING");
        assertThat(exit.observationDueAt()).isEqualTo(3420L + 7200L);

        // 观察未满 2 小时（恰差 1 秒）不得结案。
        expectBusiness(() -> service.close(
                new CloseRequest("close-early", opNo, 3420L + 7200L - 1)), "OBSERVATION_NOT_COMPLETE");

        // 观察期无症状体征，不触发加压舱。
        var vitals = service.vitals(new VitalsRequest("vit-1", opNo, "diver-a",
                3420L + 3600L, false, null)).body();
        assertThat(vitals.chamberActivated()).isFalse();
        assertThat(vitals.status()).isEqualTo("OBSERVING");

        // 5) 恰满 2 小时结案成功，为终态。
        CloseResponse closed = service.close(
                new CloseRequest("close-1", opNo, 3420L + 7200L)).body();
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.idempotentReplay()).isFalse();
        assertThat(count("active_dive_group", "")).isZero();

        // 重复结案（不同 requestId）幂等成功。
        CloseResponse closedAgain = service.close(
                new CloseRequest("close-2", opNo, 3420L + 7300L)).body();
        assertThat(closedAgain.status()).isEqualTo("CLOSED");
        assertThat(closedAgain.idempotentReplay()).isTrue();
        assertThat(closedAgain.closedAt()).isEqualTo(3420L + 7200L);

        // 结案后迟到体征：只登记迟到事件，状态保持 CLOSED，不触发加压舱。
        var late = service.vitals(new VitalsRequest("vit-late", opNo, "diver-b",
                3420L + 9000L, true, "结案后关节酸痛")).body();
        assertThat(late.late()).isTrue();
        assertThat(late.status()).isEqualTo("CLOSED");
        assertThat(late.chamberActivated()).isFalse();
        assertThat(count("vitals_record", "WHERE late = TRUE")).isEqualTo(1L);
        assertThat(count("dive_event", "WHERE event_type = 'VITALS_LATE'")).isEqualTo(1L);
        assertThat(count("dive_event", "WHERE event_type = 'CHAMBER_ACTIVATED'")).isZero();
    }

    @Test
    void overNdlApplicationRejected() {
        // 20 米档 NDL=45 分钟，46 分钟即超限。
        expectBusiness(() -> service.apply(
                application("app-over", "diver-a", "diver-b", 20, 46)), "NDL_EXCEEDED");
        assertThat(count("dive_operation", "")).isZero();
    }

    @Test
    void sameDiverTwiceRejected() {
        expectBusiness(() -> service.apply(
                application("app-same", "diver-a", "diver-a", 18, 30)), "SAME_DIVER");
    }
}
