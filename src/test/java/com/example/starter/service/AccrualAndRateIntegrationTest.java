package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.example.starter.AbstractDivingIntegrationTest;
import com.example.starter.api.CloseRequest;
import com.example.starter.api.ExitRequest;
import com.example.starter.api.PlanResponse;
import com.example.starter.api.SegmentRequest;
import com.example.starter.api.SegmentResponse;

/**
 * 24 小时（UTC）滚动窗口累计水下时长规则与速率告警的 H2 事务测试。
 * 恰 6 小时（21600 秒）合法，超出哪怕 1 秒整事务回滚；台账跨潜次累计。
 */
class AccrualAndRateIntegrationTest extends AbstractDivingIntegrationTest {

    /** 两人各提交一段 BOTTOM，返回第二名潜水员的响应。 */
    private SegmentResponse pairBottom(String reqA, String reqB, String opNo,
                                       long start, long end, int depth) {
        service.recordSegment(bottomSegment(reqA, opNo, "diver-a", depth, start, end));
        return service.recordSegment(bottomSegment(reqB, opNo, "diver-b", depth, start, end)).body();
    }

    @Test
    void exactlySixHoursAccrualIsLegalThenOneMoreSecondRejected() {
        PlanResponse plan = service.apply(
                application("app-6h", "diver-a", "diver-b", 18, 56)).body();
        String opNo = plan.operationNo();

        // 合成台账数据：第一段 3 小时 [0,10800)。
        SegmentResponse first = pairBottom("s1a", "s1b", opNo, 0L, 10_800L, 18);
        assertThat(first.effective()).isTrue();
        assertThat(first.ledgerSeconds24h()).isEqualTo(10_800L);

        // 第二段再 3 小时 [10800,21600)，累计恰 21600 秒（6 小时）合法。
        SegmentResponse second = pairBottom("s2a", "s2b", opNo, 10_800L, 21_600L, 18);
        assertThat(second.ledgerSeconds24h()).isEqualTo(21_600L);

        // 第三段哪怕 1 秒：21601 秒，拒绝且整事务回滚（不新增记录与台账）。
        long segmentsBefore = count("dive_segment", "");
        long ledgerBefore = count("diver_time_ledger", "");
        service.recordSegment(bottomSegment("s3a", opNo, "diver-a", 18, 21_600L, 21_601L));
        expectBusiness(() -> service.recordSegment(
                bottomSegment("s3b", opNo, "diver-b", 18, 21_600L, 21_601L)),
                "ACCRUAL_LIMIT_EXCEEDED");
        assertThat(count("dive_segment", "")).isEqualTo(segmentsBefore + 1); // 仅搭档的 PENDING 留存
        // 搭档的 PENDING 记录单人不生效、未入账；s3b 事务回滚未落库。
        assertThat(count("diver_time_ledger", "")).isEqualTo(ledgerBefore);
        assertThat(count("dive_segment", "WHERE pair_state = 'EFFECTIVE'")).isEqualTo(4L);
    }

    @Test
    void accrualCarriesAcrossOperationsWithin24Hours() throws Exception {
        // 潜次 1：56 分钟水底（下潜 60s + 水底 3360s = 3420s），走完流程结案释放占组。
        PlanResponse p1 = service.apply(application("app-x1", "diver-a", "diver-b", 18, 56)).body();
        String op1 = p1.operationNo();
        service.recordSegment(descendSegment("x1d-a", op1, "diver-a", 0, 18, 0, 60));
        service.recordSegment(descendSegment("x1d-b", op1, "diver-b", 0, 18, 0, 60));
        pairBottom("x1b-a", "x1b-b", op1, 60L, 3_420L, 18);
        service.exit(new ExitRequest("x1-exit", op1, 3_420L));
        service.close(new CloseRequest("x1-close", op1, 3_420L + 7_200L));
        assertThat(count("active_dive_group", "")).isZero();

        // 潜次 2：同组重新申请受理；首段 18180 秒，3420+18180=恰 21600。
        PlanResponse p2 = service.apply(application("app-x2", "diver-a", "diver-b", 18, 56)).body();
        String op2 = p2.operationNo();
        SegmentResponse within = pairBottom("x2-a", "x2-b", op2, 10_800L, 28_980L, 18);
        assertThat(within.ledgerSeconds24h()).isEqualTo(21_600L);

        // 再多 1 秒即超限（台账含潜次 1 的 3420 秒）。
        service.recordSegment(bottomSegment("x3-a", op2, "diver-a", 18, 28_980L, 28_981L));
        expectBusiness(() -> service.recordSegment(
                bottomSegment("x3-b", op2, "diver-b", 18, 28_980L, 28_981L)),
                "ACCRUAL_LIMIT_EXCEEDED");
    }

    @Test
    void overSpeedRaisesAlarmEventsButSegmentIsAccepted() {
        PlanResponse plan = service.apply(
                application("app-speed", "diver-a", "diver-b", 18, 56)).body();
        String opNo = plan.operationNo();
        // 18 米 / 50 秒 = 21.6 m/min，超速：记录受理但产生两名潜水员的速率告警事件。
        service.recordSegment(ascendSegment("sp-a", opNo, "diver-a", 18, 0, 0, 50));
        SegmentResponse fast = service.recordSegment(
                ascendSegment("sp-b", opNo, "diver-b", 18, 0, 0, 50)).body();
        assertThat(fast.rateAlarm()).isTrue();
        assertThat(fast.rateMPerMin()).isEqualByComparingTo("21.6000");
        assertThat(fast.effective()).isTrue();
        assertThat(count("dive_event", "WHERE event_type = 'RATE_ALARM'")).isEqualTo(2L);
    }

    @Test
    void singleSegmentNeverGoesEffectiveAndBlocksExit() {
        PlanResponse plan = service.apply(
                application("app-single", "diver-a", "diver-b", 18, 56)).body();
        String opNo = plan.operationNo();
        // 先成对完成下潜进入 EXECUTING。
        service.recordSegment(descendSegment("pair-a", opNo, "diver-a", 0, 18, 0, 60));
        service.recordSegment(descendSegment("pair-b", opNo, "diver-b", 0, 18, 0, 60));
        // 水底段只有 diver-a 一人提交：PENDING，不生效。
        SegmentResponse only = service.recordSegment(
                bottomSegment("only-a", opNo, "diver-a", 18, 60, 120)).body();
        assertThat(only.pairState()).isEqualTo("PENDING");
        // 已生效台账保持 2 条（下潜），单人水底段不入账。
        assertThat(count("diver_time_ledger", "")).isEqualTo(2L);
        // 带未配对记录不得出水。
        expectBusiness(() -> service.exit(new ExitRequest("only-exit", opNo, 120L)),
                "PENDING_SEGMENT_EXISTS");
    }

    @Test
    void outsiderDiverCannotRecordIntoGroup() {
        PlanResponse plan = service.apply(
                application("app-out", "diver-a", "diver-b", 18, 56)).body();
        expectBusiness(() -> service.recordSegment(
                descendSegment("out-1", plan.operationNo(), "diver-c", 0, 18, 0, 60)),
                "DIVER_NOT_IN_GROUP");
    }

    private SegmentRequest ascendSegment(String requestId, String opNo, String diver,
                                         int from, int to, long start, long end) {
        return new SegmentRequest(requestId, opNo, diver, "ASCEND", from, to, start, end);
    }
}
