package com.example.starter.deicing.service;

import com.example.starter.deicing.AbstractDeicingIntegrationTest;
import com.example.starter.deicing.api.ApplyRequest;
import com.example.starter.deicing.api.AssignmentView;
import com.example.starter.deicing.api.CancelRequest;
import com.example.starter.deicing.api.DelayRequest;
import com.example.starter.deicing.api.DepartRequest;
import com.example.starter.deicing.api.QueueItemResponse;
import com.example.starter.deicing.api.SnapshotResponse;
import com.example.starter.deicing.exception.ApiException;
import com.example.starter.deicing.model.DeiceType;
import com.example.starter.deicing.model.QueueStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 除冰排队领域服务 H2 集成测试：覆盖公开主流程、HOT 边界、插队禁止、
 * 幂等重放/异参冲突、HOT 风险与终态幂等等关键业务规则。
 */
class DeicingServiceIntegrationTest extends AbstractDeicingIntegrationTest {

    private ApplyRequest oneStep(String requestId, String flight, int bays, long departure) {
        return new ApplyRequest(requestId, flight, "S-" + flight, DeiceType.ONE_STEP, bays, departure);
    }

    private ApplyRequest twoStep(String requestId, String flight, int bays, long departure) {
        return new ApplyRequest(requestId, flight, "S-" + flight, DeiceType.TWO_STEP, bays, departure);
    }

    @Test
    void mainFlowOneStepApplyDispatchStartCompleteDepart() {
        long departure = BASE + 120 * MIN;
        DeicingService.IdentifiedResult created = service.apply(
                oneStep("R-1", "CA1001", 1, departure));
        assertThat(created.httpStatus()).isEqualTo(201);
        assertThat(created.view().status()).isEqualTo(QueueStatus.WAITING);
        assertThat(created.view().deadlineTs()).isEqualTo(departure - 20 * MIN);

        DeicingService.IdentifiedResult dispatched = service.dispatch("R-1");
        assertThat(dispatched.httpStatus()).isEqualTo(200);
        assertThat(dispatched.view().status()).isEqualTo(QueueStatus.SCHEDULED);
        assertThat(dispatched.view().assignments()).hasSize(1);
        AssignmentView assignment = dispatched.view().assignments().get(0);
        assertThat(assignment.startTs()).isEqualTo(BASE);
        assertThat(assignment.endTs()).isEqualTo(BASE + 20 * MIN);

        QueueItemResponse working = service.startWork("R-1");
        assertThat(working.status()).isEqualTo(QueueStatus.IN_PROGRESS);
        assertThat(working.actualStartTs()).isEqualTo(BASE);

        clock.advance(20 * MIN);
        QueueItemResponse deiced = service.completeWork("R-1");
        assertThat(deiced.status()).isEqualTo(QueueStatus.DEICED);
        assertThat(deiced.actualFinishTs()).isEqualTo(BASE + 20 * MIN);
        assertThat(deiced.hotExpiresTs()).isNull();

        DeicingService.IdentifiedResult gone = service.depart(
                new DepartRequest("R-1", departure));
        assertThat(gone.httpStatus()).isEqualTo(200);
        assertThat(gone.view().status()).isEqualTo(QueueStatus.DEPARTED);
        assertThat(countOf("flight_active")).isZero();
    }

    @Test
    void halfOpenOccupancyTouchesAtEndpointAndOverlapWaits() {
        // A：两步法 1 车位，排定 [BASE, BASE+30)
        service.apply(twoStep("A", "CA2001", 1, BASE + 120 * MIN));
        QueueItemResponse aPlan = service.dispatch("A").view();
        assertThat(aPlan.assignments().get(0).startTs()).isEqualTo(BASE);
        assertThat(aPlan.assignments().get(0).endTs()).isEqualTo(BASE + 30 * MIN);

        // D 也需要 2 个车位：[BASE,BASE+30) 内 B1/T1 被占，必须等到 BASE+30（端点相接）
        service.apply(twoStep("D", "CA2004", 2, BASE + 300 * MIN));
        QueueItemResponse dPlan = service.dispatch("D").view();
        assertThat(dPlan.assignments()).hasSize(2);
        assertThat(dPlan.assignments()).allSatisfy(a -> {
            assertThat(a.startTs()).isEqualTo(BASE + 30 * MIN);
            assertThat(a.endTs()).isEqualTo(BASE + 60 * MIN);
        });

        List<AssignmentView> timeline = service.timeline(null, BASE, BASE + 60 * MIN);
        assertThat(timeline).hasSize(3);
        // 时间线可复算、重复查询稳定
        assertThat(service.timeline(null, BASE, BASE + 60 * MIN)).isEqualTo(timeline);
    }

    @Test
    void twoStepDepartureExactlyAtHotEndpointIsValid() {
        service.apply(twoStep("H1", "CA3001", 1, BASE + 120 * MIN));
        service.dispatch("H1");
        service.startWork("H1");
        clock.advance(30 * MIN);
        QueueItemResponse deiced = service.completeWork("H1");
        long hotEndpoint = BASE + 60 * MIN;
        assertThat(deiced.hotExpiresTs()).isEqualTo(hotEndpoint);

        // 恰在 HOT 终点起飞：有效
        DeicingService.IdentifiedResult result = service.depart(
                new DepartRequest("H1", hotEndpoint));
        assertThat(result.view().status()).isEqualTo(QueueStatus.DEPARTED);
    }

    @Test
    void twoStepDepartureAfterHotEndpointExpiresAndRequiresRedeice() {
        service.apply(twoStep("H2", "CA3002", 1, BASE + 120 * MIN));
        service.dispatch("H2");
        service.startWork("H2");
        clock.advance(30 * MIN);
        service.completeWork("H2");
        long hotEndpoint = BASE + 60 * MIN;

        // 超过 HOT 终点 1 毫秒：失效，须重新除冰
        DeicingService.IdentifiedResult result = service.depart(
                new DepartRequest("H2", hotEndpoint + 1));
        assertThat(result.view().status()).isEqualTo(QueueStatus.HOT_EXPIRED);
        assertThat(countOf("flight_active")).isZero();

        // 失效后同航班可以用新 requestId 重新申请
        DeicingService.IdentifiedResult retry = service.apply(
                twoStep("H2-R", "CA3002", 1, BASE + 240 * MIN));
        assertThat(retry.httpStatus()).isEqualTo(201);
    }

    @Test
    void sameRequestIdReplayReturnsFirstResultAndDifferentParamsConflict() {
        ApplyRequest first = twoStep("IDEM-1", "CA4001", 1, BASE + 120 * MIN);
        DeicingService.IdentifiedResult r1 = service.apply(first);
        DeicingService.IdentifiedResult r2 = service.apply(first);
        assertThat(r2.httpStatus()).isEqualTo(201);
        assertThat(r2.view()).isEqualTo(r1.view());
        assertThat(countOf("queue_request")).isEqualTo(1L);

        // 同键异参：409 且队列不变
        ApplyRequest tampered = twoStep("IDEM-1", "CA4001", 1, BASE + 180 * MIN);
        assertThatThrownBy(() -> service.apply(tampered))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(409));
        assertThat(countOf("queue_request")).isEqualTo(1L);
        assertThat(service.get("IDEM-1").targetDepartureTs()).isEqualTo(BASE + 120 * MIN);
    }

    @Test
    void dispatchIsIdempotentAndDoesNotDoubleBook() {
        service.apply(twoStep("IDEM-D", "CA4002", 1, BASE + 120 * MIN));
        QueueItemResponse first = service.dispatch("IDEM-D").view();
        QueueItemResponse replay = service.dispatch("IDEM-D").view();
        assertThat(replay).isEqualTo(first);
        // 占用只排定一次
        assertThat(countOf("deicing_assignment")).isEqualTo(1L);

        // 出队异参场景由 requestId 唯一定位；对已排定记录再次出队返回首次排定
        assertThat(service.timeline(null, null, null)).hasSize(1);
    }

    @Test
    void cannotDispatchAheadOfHigherPriorityHead() {
        // Y 截止时刻更早（临近起飞优先）；X 后申请且截止更晚
        service.apply(twoStep("X", "CA5001", 1, BASE + 240 * MIN));
        service.apply(twoStep("Y", "CA5002", 1, BASE + 120 * MIN));

        assertThatThrownBy(() -> service.dispatch("X"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo("NOT_QUEUE_HEAD"));
        assertThat(service.get("X").status()).isEqualTo(QueueStatus.WAITING);

        // 队首 Y 先出队占用 B1/T1 [BASE,BASE+30)；X 随后出队，因 B2/T2 仍空闲可并行开始，
        // 但不能越过队首先于 Y 出队（插队禁止体现在状态机而非资源等待）
        QueueItemResponse yPlan = service.dispatch("Y").view();
        assertThat(yPlan.assignments().get(0).startTs()).isEqualTo(BASE);
        assertThat(yPlan.assignments().get(0).bayId()).isEqualTo("B1");
        QueueItemResponse xPlan = service.dispatch("X").view();
        assertThat(xPlan.assignments().get(0).startTs()).isEqualTo(BASE);
        assertThat(xPlan.assignments().get(0).bayId()).isEqualTo("B2");
    }

    @Test
    void startedWorkCannotBeCancelledAndCannotBeJumped() {
        // A 作业开始后锁定 B1/T1；新到的紧急航班 Z 需要 2 车位，无法插到 A 之前
        service.apply(twoStep("A", "CA6001", 1, BASE + 120 * MIN));
        service.dispatch("A");
        service.startWork("A");

        assertThatThrownBy(() -> service.cancel(new CancelRequest("A", "临时取消")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo("WORK_IN_PROGRESS"));

        service.apply(twoStep("Z", "CA6002", 2, BASE + 61 * MIN));
        QueueItemResponse zPlan = service.dispatch("Z").view();
        // A 占用 [BASE,BASE+30) ACTIVE，Z 最早只能 BASE+30 开始，作业不得前插
        assertThat(zPlan.assignments()).allSatisfy(a ->
                assertThat(a.startTs()).isGreaterThanOrEqualTo(BASE + 30 * MIN));
    }

    @Test
    void predecessorDelayCausesHotRiskWithEarliestRescheduleTime() {
        // A 两步法 BASE~BASE+30 完成，HOT 至 BASE+60
        service.apply(twoStep("A", "CA7001", 1, BASE + 55 * MIN));
        service.dispatch("A");
        service.startWork("A");
        clock.advance(30 * MIN);
        service.completeWork("A");

        // B 在 BASE+30 起占用两个车位/两辆车至 BASE+50（一步法 20 分钟）
        service.apply(oneStep("B", "CA7002", 2, BASE + 300 * MIN));
        service.dispatch("B");
        service.startWork("B");

        // BASE+40 回报 A 因前序延误预计 BASE+95 起飞，超出 HOT：主动置风险
        clock.advance(10 * MIN);
        QueueItemResponse risky = service.reportDelay(
                new DelayRequest("A", BASE + 95 * MIN));
        assertThat(risky.status()).isEqualTo(QueueStatus.HOT_RISK);
        // B 作业锁定到 BASE+50，A 最早只能在 BASE+50 重新除冰，不得静默放行
        assertThat(risky.earliestReschedTs()).isEqualTo(BASE + 50 * MIN);

        // 到 BASE+50 确认重新除冰：新轮次排定 [50,80)，状态回 SCHEDULED
        clock.advance(10 * MIN);
        QueueItemResponse rescheduled = service.reschedule("A");
        assertThat(rescheduled.status()).isEqualTo(QueueStatus.SCHEDULED);
        assertThat(rescheduled.roundNo()).isEqualTo(2);
        assertThat(rescheduled.hotExpiresTs()).isNull();
        assertThat(rescheduled.assignments().stream().filter(a -> a.roundNo() == 2).count())
                .isEqualTo(1);
        assertThat(rescheduled.assignments().stream().filter(a -> a.roundNo() == 2).findFirst().orElseThrow())
                .satisfies(a -> {
                    assertThat(a.startTs()).isEqualTo(BASE + 50 * MIN);
                    assertThat(a.endTs()).isEqualTo(BASE + 80 * MIN);
                });
    }

    @Test
    void delayThatFitsHotKeepsDeicedAndRiskCanClear() {
        // 目标起飞恰为预计完成后 HOT 终点（BASE+60）：dispatch [BASE,BASE+30)，完成即 BASE+30
        service.apply(twoStep("A", "CA7003", 1, BASE + 60 * MIN));
        service.dispatch("A");
        service.startWork("A");
        clock.advance(30 * MIN);
        service.completeWork("A");

        // 预计起飞恰在 HOT 终点：不触发风险
        QueueItemResponse stillDeiced = service.reportDelay(
                new DelayRequest("A", BASE + 60 * MIN));
        assertThat(stillDeiced.status()).isEqualTo(QueueStatus.DEICED);
        assertThat(stillDeiced.earliestReschedTs()).isNull();
    }

    @Test
    void hotRiskClearsWhenExpectedDepartureFallsBackWithinHot() {
        // 目标起飞 BASE+60（=首次 HOT 终点）
        service.apply(twoStep("A", "CA7004", 1, BASE + 60 * MIN));
        service.dispatch("A");
        service.startWork("A");
        clock.advance(30 * MIN);
        service.completeWork("A");

        // 延误到 BASE+95（超过 HOT 终点 BASE+60）：置风险
        QueueItemResponse risky = service.reportDelay(new DelayRequest("A", BASE + 95 * MIN));
        assertThat(risky.status()).isEqualTo(QueueStatus.HOT_RISK);
        assertThat(risky.earliestReschedTs()).isNotNull();

        // 时刻回落到恰在 HOT 终点（仍不早于目标起飞）：风险解除
        QueueItemResponse cleared = service.reportDelay(new DelayRequest("A", BASE + 60 * MIN));
        assertThat(cleared.status()).isEqualTo(QueueStatus.DEICED);
        assertThat(cleared.earliestReschedTs()).isNull();
    }

    @Test
    void terminalOperationsAreIdempotentAndLateDepartureDoesNotReviveCancelled() {
        service.apply(twoStep("T1", "CA8001", 1, BASE + 120 * MIN));
        service.dispatch("T1");
        service.startWork("T1");
        clock.advance(30 * MIN);
        service.completeWork("T1");

        long departure = BASE + 55 * MIN;
        DeicingService.IdentifiedResult first = service.depart(new DepartRequest("T1", departure));
        DeicingService.IdentifiedResult replay = service.depart(new DepartRequest("T1", departure));
        assertThat(first.view().status()).isEqualTo(QueueStatus.DEPARTED);
        assertThat(replay.view().status()).isEqualTo(QueueStatus.DEPARTED);
        // 终态后的取消不改写状态（幂等成功，不复活、不转移）
        DeicingService.IdentifiedResult lateCancel = service.cancel(new CancelRequest("T1", "晚到取消"));
        assertThat(lateCancel.view().status()).isEqualTo(QueueStatus.DEPARTED);

        // 另一条：先取消，再迟到起飞回报，仍为 CANCELLED
        service.apply(twoStep("T2", "CA8002", 1, BASE + 120 * MIN));
        service.cancel(new CancelRequest("T2", "航班取消"));
        DeicingService.IdentifiedResult lateDepart = service.depart(
                new DepartRequest("T2", BASE + 100 * MIN));
        assertThat(lateDepart.httpStatus()).isEqualTo(200);
        assertThat(lateDepart.view().status()).isEqualTo(QueueStatus.CANCELLED);
        // 重复取消幂等成功
        assertThat(service.cancel(new CancelRequest("T2", "再次取消")).view().status())
                .isEqualTo(QueueStatus.CANCELLED);
    }

    @Test
    void cancelledScheduledRequestReleasesOccupancyForOthers() {
        service.apply(twoStep("C1", "CA9001", 2, BASE + 200 * MIN));
        QueueItemResponse plan = service.dispatch("C1").view();
        assertThat(plan.assignments()).hasSize(2);

        service.cancel(new CancelRequest("C1", "资源释放"));
        assertThat(service.get("C1").status()).isEqualTo(QueueStatus.CANCELLED);
        // RESERVED 占用已释放，时间线（有效占用）为空
        assertThat(service.timeline(null, null, null)).isEmpty();
        assertThat(countOf("flight_active")).isZero();
    }

    @Test
    void snapshotIsStableAndPriorityOrdered() {
        service.apply(twoStep("LATE", "CA9101", 1, BASE + 240 * MIN));
        service.apply(twoStep("EARLY", "CA9102", 1, BASE + 120 * MIN));

        SnapshotResponse s1 = service.snapshot();
        SnapshotResponse s2 = service.snapshot();
        assertThat(s1.items()).hasSize(2);
        assertThat(s1.items()).isEqualTo(s2.items());
        // 截止时刻早者排在前
        assertThat(s1.items().get(0).requestId()).isEqualTo("EARLY");
        assertThat(s1.items().get(1).requestId()).isEqualTo("LATE");
    }

    @Test
    void sameFlightSecondActiveApplicationRejected() {
        service.apply(twoStep("F1", "CA9201", 1, BASE + 120 * MIN));
        assertThatThrownBy(() -> service.apply(twoStep("F1-2", "CA9201", 1, BASE + 180 * MIN)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo("FLIGHT_ALREADY_ACTIVE"));
        assertThat(countOf("queue_request")).isEqualTo(1L);
    }

    @Test
    void pastDepartureApplicationRejected() {
        assertThatThrownBy(() -> service.apply(
                        twoStep("PAST", "CA9301", 1, BASE - 1)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo("DEPARTURE_IN_PAST"));
    }
}
