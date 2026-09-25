package com.example.starter.deicing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.deicing.domain.DeicingType;
import com.example.starter.deicing.domain.RequestStatus;
import com.example.starter.deicing.web.ApiException;
import com.example.starter.deicing.web.dto.ApplicationRequest;
import com.example.starter.deicing.web.dto.MutationResponse;
import com.example.starter.deicing.web.dto.QueueItemView;
import com.example.starter.deicing.web.dto.TakeoffResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 基于 H2（MODE=MySQL）的核心业务与事务测试。
 */
@SpringBootTest
class DeicingServiceH2Test {

    private static final Instant T0 = Instant.parse("2026-09-25T10:00:00Z");

    @Autowired
    private DeicingService service;
    @Autowired
    private TimeSource timeSource;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM deicing_allocation");
        jdbcTemplate.update("DELETE FROM deicing_request");
        timeSource.pin(Clock.fixed(T0, ZoneOffset.UTC));
    }

    private ApplicationRequest oneStep(String requestId, String flight, int durationMinutes,
                                       Instant takeoff) {
        return new ApplicationRequest(requestId, flight, "STAND-" + flight,
                DeicingType.ONE_STEP, 1, durationMinutes, takeoff);
    }

    private ApplicationRequest twoStep(String requestId, String flight, int requiredBays,
                                       int durationMinutes, Instant takeoff) {
        return new ApplicationRequest(requestId, flight, "STAND-" + flight,
                DeicingType.TWO_STEP, requiredBays, durationMinutes, takeoff);
    }

    @Test
    void oneStepHappyFlowFromApplyToTakeoff() {
        Instant takeoff = T0.plus(Duration.ofMinutes(90));
        MutationResponse applied = service.apply(oneStep("REQ-1", "CA101", 30, takeoff));
        assertThat(applied.replay()).isFalse();
        assertThat(applied.item().status()).isEqualTo(RequestStatus.QUEUED);

        MutationResponse dequeued = service.dequeue("REQ-1");
        assertThat(dequeued.item().status()).isEqualTo(RequestStatus.SCHEDULED);
        assertThat(dequeued.bays()).containsExactly("BAY-01");
        assertThat(dequeued.item().scheduledStart()).isEqualTo(T0);
        assertThat(dequeued.item().scheduledEnd()).isEqualTo(T0.plus(Duration.ofMinutes(30)));

        MutationResponse started = service.startJob("REQ-1");
        assertThat(started.item().status()).isEqualTo(RequestStatus.IN_PROGRESS);
        assertThat(started.bays()).containsExactly("BAY-01");

        TakeoffResponse takeoffResponse = service.reportTakeoff("REQ-1", T0.plus(Duration.ofMinutes(35)));
        assertThat(takeoffResponse.valid()).isTrue();
        assertThat(takeoffResponse.replay()).isFalse();
        assertThat(takeoffResponse.item().status()).isEqualTo(RequestStatus.TAKEN_OFF);
    }

    @Test
    void endpointTouchingAllocationIsAllowed() {
        // A 占用全部 4 个车位 [10:00,10:30)；B 截止时刻恰为 10:30，应在 10:30 端点相接开工
        service.apply(oneStep("REQ-A", "CA101", 30, T0.plus(Duration.ofMinutes(60))));
        jdbcTemplate.update("UPDATE deicing_request SET required_bays = 4 WHERE request_id = 'REQ-A'");
        MutationResponse dequeuedA = service.dequeue("REQ-A");
        assertThat(dequeuedA.bays()).hasSize(4);

        service.apply(oneStep("REQ-B", "CA202", 30, T0.plus(Duration.ofMinutes(60))));
        MutationResponse dequeuedB = service.dequeue("REQ-B");
        assertThat(dequeuedB.item().status()).isEqualTo(RequestStatus.SCHEDULED);
        assertThat(dequeuedB.item().scheduledStart()).isEqualTo(T0.plus(Duration.ofMinutes(30)));
        assertThat(dequeuedB.bays()).containsExactly("BAY-01");
    }

    @Test
    void priorityByDeadlineThenSequenceAndNoCuttingIn() {
        // A 先申请但截止时刻晚；B 后申请但截止时刻更早 → B 应为队首
        service.apply(oneStep("REQ-A", "CA101", 30, T0.plus(Duration.ofMinutes(120))));
        service.apply(oneStep("REQ-B", "CA202", 30, T0.plus(Duration.ofMinutes(60))));

        assertThatThrownBy(() -> service.dequeue("REQ-A"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("REQ-B");

        MutationResponse dequeuedB = service.dequeue("REQ-B");
        assertThat(dequeuedB.item().status()).isEqualTo(RequestStatus.SCHEDULED);

        // B 已开始作业后，A 成为队首可正常出队，使用与 B 并行空闲的 BAY-02
        service.startJob("REQ-B");
        MutationResponse dequeuedA = service.dequeue("REQ-A");
        assertThat(dequeuedA.item().status()).isEqualTo(RequestStatus.SCHEDULED);
        assertThat(dequeuedA.item().scheduledStart()).isEqualTo(T0);
        assertThat(dequeuedA.bays()).containsExactly("BAY-02");
    }

    @Test
    void twoStepHotExactlyAtDeadlineIsValid() {
        // CA101 HOT=15 分钟：10:00 申请目标 10:25 起飞、10 分钟作业 → 作业 [10:00,10:10)，
        // HOT 终点 10:25；恰在 10:25 起飞视为有效
        service.apply(twoStep("REQ-H1", "CA101", 1, 10, T0.plus(Duration.ofMinutes(25))));
        service.dequeue("REQ-H1");
        service.startJob("REQ-H1");

        TakeoffResponse response = service.reportTakeoff("REQ-H1", T0.plus(Duration.ofMinutes(25)));
        assertThat(response.valid()).isTrue();
        assertThat(response.hotDeadline()).isEqualTo(T0.plus(Duration.ofMinutes(25)));
        assertThat(response.item().status()).isEqualTo(RequestStatus.TAKEN_OFF);
    }

    @Test
    void twoStepHotExpiredRequiresRedeice() {
        // CA202 HOT=20 分钟：作业 [10:00,10:10)，HOT 终点 10:30
        service.apply(twoStep("REQ-H2", "CA202", 1, 10, T0.plus(Duration.ofMinutes(30))));
        service.dequeue("REQ-H2");
        service.startJob("REQ-H2");
        assertThat(service.get("REQ-H2").scheduledEnd()).isEqualTo(T0.plus(Duration.ofMinutes(10)));

        // 晚 HOT 终点 1 秒 → 失效
        TakeoffResponse late = service.reportTakeoff(
                "REQ-H2", T0.plus(Duration.ofMinutes(30)).plusSeconds(1));
        assertThat(late.valid()).isFalse();
        assertThat(late.item().status()).isEqualTo(RequestStatus.HOT_EXPIRED);

        // 终态后同航班可以重新申请除冰
        MutationResponse reapply = service.apply(
                twoStep("REQ-H2-RE", "CA202", 1, 10, T0.plus(Duration.ofMinutes(90))));
        assertThat(reapply.item().status()).isEqualTo(RequestStatus.QUEUED);
    }

    @Test
    void sameKeyReplayReturnsFirstResultAndDifferentParamsConflict() {
        ApplicationRequest first = oneStep("REQ-IDEM", "CA101", 30,
                T0.plus(Duration.ofMinutes(60)));
        service.apply(first);
        MutationResponse dequeued = service.dequeue("REQ-IDEM");
        assertThat(dequeued.replay()).isFalse();

        // 同键同参重放出队：返回首次结果且 replay=true
        MutationResponse replay = service.dequeue("REQ-IDEM");
        assertThat(replay.replay()).isTrue();
        assertThat(replay.item().scheduledStart()).isEqualTo(dequeued.item().scheduledStart());
        assertThat(replay.bays()).isEqualTo(dequeued.bays());

        // 同键异参申请 → 409
        ApplicationRequest tampered = new ApplicationRequest("REQ-IDEM", "CA101", "STAND-CA101",
                DeicingType.ONE_STEP, 1, 25, T0.plus(Duration.ofMinutes(60)));
        assertThatThrownBy(() -> service.apply(tampered))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));

        // 队列不被改变：时长仍是 30
        QueueItemView view = service.get("REQ-IDEM");
        assertThat(view.durationMinutes()).isEqualTo(30);
        assertThat(view.status()).isEqualTo(RequestStatus.SCHEDULED);
    }

    @Test
    void lateTakeoffDoesNotReviveCancelledItem() {
        service.apply(oneStep("REQ-C", "MU510", 20, T0.plus(Duration.ofMinutes(60))));
        service.dequeue("REQ-C");

        MutationResponse cancel1 = service.cancel("REQ-C");
        assertThat(cancel1.item().status()).isEqualTo(RequestStatus.CANCELLED);
        // 重复取消幂等成功
        MutationResponse cancel2 = service.cancel("REQ-C");
        assertThat(cancel2.replay()).isTrue();
        assertThat(cancel2.item().status()).isEqualTo(RequestStatus.CANCELLED);

        // 迟到起飞回报：不复活，幂等返回 valid=false
        TakeoffResponse late = service.reportTakeoff("REQ-C", T0.plus(Duration.ofMinutes(40)));
        assertThat(late.replay()).isTrue();
        assertThat(late.valid()).isFalse();
        assertThat(service.get("REQ-C").status()).isEqualTo(RequestStatus.CANCELLED);
    }

    @Test
    void hotRiskMarkedWhenPriorDelayBlocksFlightAndRescheduleRecovers() {
        // 长作业 X 占满全部车位 [10:00,11:00)
        ApplicationRequest x = oneStep("REQ-X", "CA101", 60, T0.plus(Duration.ofMinutes(120)));
        service.apply(x);
        jdbcTemplate.update("UPDATE deicing_request SET required_bays = 4 WHERE request_id = 'REQ-X'");
        service.dequeue("REQ-X");

        // Y 两步法 HOT=15，10 分钟作业，想 10:40 起飞（截止 10:30）；车位 11:00 才空 → HOT 风险
        service.apply(twoStep("REQ-Y", "CA202", 1, 10, T0.plus(Duration.ofMinutes(40))));
        MutationResponse risky = service.dequeue("REQ-Y");
        assertThat(risky.item().status()).isEqualTo(RequestStatus.HOT_RISK);
        assertThat(risky.item().hotRiskReason()).isEqualTo("LATE_COMPLETION");
        assertThat(risky.item().earliestReassignTime()).isEqualTo(T0.plus(Duration.ofMinutes(60)));

        // 未重排前不得起飞
        assertThatThrownBy(() -> service.reportTakeoff("REQ-Y", T0.plus(Duration.ofMinutes(40))))
                .isInstanceOf(ApiException.class);

        // 重排到 11:30 起飞后重新出队成功
        MutationResponse rescheduled = service.reschedule(
                "REQ-Y", T0.plus(Duration.ofMinutes(90)));
        assertThat(rescheduled.item().status()).isEqualTo(RequestStatus.QUEUED);
        assertThat(rescheduled.item().targetTakeoffTime()).isEqualTo(T0.plus(Duration.ofMinutes(90)));

        MutationResponse reDequeued = service.dequeue("REQ-Y");
        assertThat(reDequeued.item().status()).isEqualTo(RequestStatus.SCHEDULED);
        assertThat(reDequeued.item().scheduledStart()).isEqualTo(T0.plus(Duration.ofMinutes(60)));
    }

    @Test
    void hotRiskRescheduleCannotCutInAheadOfEarlierDeadline() {
        // X 占满全部车位 [10:00,11:00)
        service.apply(oneStep("REQ-X", "CA101", 60, T0.plus(Duration.ofMinutes(120))));
        jdbcTemplate.update("UPDATE deicing_request SET required_bays = 4 WHERE request_id = 'REQ-X'");
        service.dequeue("REQ-X");

        // Y 无法在截止前完成 → HOT_RISK，重排到 12:00 起飞（截止 11:50）
        service.apply(twoStep("REQ-Y", "CA202", 1, 10, T0.plus(Duration.ofMinutes(40))));
        assertThat(service.dequeue("REQ-Y").item().status()).isEqualTo(RequestStatus.HOT_RISK);
        service.reschedule("REQ-Y", T0.plus(Duration.ofMinutes(120)));

        // Z 截止时刻更早（10:45 截止，10:55 起飞），车位 11:00 才空 → 同样 HOT_RISK，但必须排在 Y 前面
        service.apply(twoStep("REQ-Z", "MU510", 1, 10, T0.plus(Duration.ofMinutes(55))));

        assertThatThrownBy(() -> service.dequeue("REQ-Y"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("REQ-Z");

        // Z 出队后因车位 11:00 才空、无法在截止前完成而进入 HOT_RISK，仍位于 Y 之前
        assertThat(service.dequeue("REQ-Z").item().status()).isEqualTo(RequestStatus.HOT_RISK);
        assertThat(service.snapshot().items().stream().findFirst()
                .orElseThrow().requestId()).isEqualTo("REQ-Z");
    }

    @Test
    void terminalTakeoffReportIsIdempotent() {
        service.apply(oneStep("REQ-T", "CZ303", 15, T0.plus(Duration.ofMinutes(60))));
        service.dequeue("REQ-T");
        service.startJob("REQ-T");
        service.reportTakeoff("REQ-T", T0.plus(Duration.ofMinutes(20)));

        TakeoffResponse replay = service.reportTakeoff("REQ-T", T0.plus(Duration.ofMinutes(120)));
        assertThat(replay.replay()).isTrue();
        assertThat(replay.valid()).isTrue();
        assertThat(replay.item().actualTakeoffTime()).isEqualTo(T0.plus(Duration.ofMinutes(20)));
    }

    @Test
    void snapshotAndTimelineAreStableAndRecomputable() {
        service.apply(oneStep("REQ-S1", "CA101", 30, T0.plus(Duration.ofMinutes(90))));
        service.apply(oneStep("REQ-S2", "CA202", 20, T0.plus(Duration.ofMinutes(60))));
        service.dequeue("REQ-S2");

        var first = service.snapshot();
        var second = service.snapshot();
        assertThat(second.items()).extracting(QueueItemView::requestId)
                .containsExactlyElementsOf(first.items().stream().map(QueueItemView::requestId).toList());
        // 截止时刻更早的 S2 排在前面
        assertThat(first.items()).extracting(QueueItemView::requestId)
                .containsExactly("REQ-S2", "REQ-S1");

        var timeline1 = service.timeline();
        var timeline2 = service.timeline();
        assertThat(timeline1.bays()).hasSize(4);
        var bay01 = timeline1.bays().get(0);
        assertThat(bay01.bayCode()).isEqualTo("BAY-01");
        assertThat(bay01.occupancies()).hasSize(1);
        assertThat(bay01.occupancies().get(0).requestId()).isEqualTo("REQ-S2");
        assertThat(bay01.occupancies().get(0).end())
                .isEqualTo(T0.plus(Duration.ofMinutes(20)));
        assertThat(timeline2).isEqualTo(timeline1);

        // 取消后占用从时间线移除
        service.cancel("REQ-S2");
        assertThat(service.timeline().bays().get(0).occupancies()).isEmpty();
    }

    @Test
    void missingTimetableForTwoStepRejected() {
        // 合成时刻表没有该航班 → 申请两步法被拒
        assertThatThrownBy(() -> service.apply(
                        twoStep("REQ-X9", "NO999", 1, 10, T0.plus(Duration.ofMinutes(60)))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("HOT");
    }
}
