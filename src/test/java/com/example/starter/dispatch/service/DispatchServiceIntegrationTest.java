package com.example.starter.dispatch.service;

import com.example.starter.dispatch.MutableDispatchClock;
import com.example.starter.dispatch.TestClockConfiguration;
import com.example.starter.dispatch.api.DispatchException;
import com.example.starter.dispatch.api.GateStepView;
import com.example.starter.dispatch.api.IssueOrderRequest;
import com.example.starter.dispatch.api.NotificationView;
import com.example.starter.dispatch.api.OrderView;
import com.example.starter.dispatch.api.ReceiptRequest;
import com.example.starter.dispatch.domain.NotificationType;
import com.example.starter.dispatch.domain.OrderStatus;
import com.example.starter.dispatch.repo.DispatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 调度令服务 H2 事务测试：主流程、顺序执行、幂等冲突、唯一在令、
 * 撤销/中止、偏差告警与超时升级。
 */
@SpringBootTest(classes = {
        com.example.starter.StarterApplication.class,
        TestClockConfiguration.class})
class DispatchServiceIntegrationTest {

    private static final Instant T0 = TestClockConfiguration.BASE_TIME;

    @Autowired
    private DispatchService service;
    @Autowired
    private DispatchRepository repository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MutableDispatchClock clock;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM active_gate_lock");
        jdbcTemplate.update("DELETE FROM dispatch_notification");
        jdbcTemplate.update("DELETE FROM gate_step");
        jdbcTemplate.update("DELETE FROM dispatch_order");
    }

    private IssueOrderRequest twoGateOrder(String requestId, Instant deadline) {
        return new IssueOrderRequest(requestId, List.of("G1", "G2"),
                List.of(new BigDecimal("50.000"), new BigDecimal("40.000")), deadline);
    }

    private ReceiptRequest receipt(String gate, String opening, Instant at) {
        return new ReceiptRequest(gate, new BigDecimal(opening), at);
    }

    @Test
    void issueAcknowledgeAndSequentialReceiptsCompleteOrder() {
        service.issue(twoGateOrder("REQ-MAIN", T0.plusSeconds(3600)));
        assertThat(service.getOrder("REQ-MAIN").status()).isEqualTo(OrderStatus.ISSUED);

        // 未签收不得回执
        assertThatThrownBy(() -> service.receipt("REQ-MAIN", receipt("G1", "50.000", T0)))
                .isInstanceOf(DispatchException.class)
                .extracting(e -> ((DispatchException) e).code()).isEqualTo("NOT_ACKNOWLEDGED");

        service.acknowledge("REQ-MAIN");
        assertThat(service.getOrder("REQ-MAIN").status()).isEqualTo(OrderStatus.ACKNOWLEDGED);

        // 顺序约束：G1 未完成前 G2 不得执行
        assertThatThrownBy(() -> service.receipt("REQ-MAIN", receipt("G2", "40.000", T0.plusSeconds(60))))
                .isInstanceOf(DispatchException.class)
                .extracting(e -> ((DispatchException) e).code()).isEqualTo("GATE_NOT_IN_SEQUENCE");

        service.receipt("REQ-MAIN", receipt("G1", "50.000", T0.plusSeconds(60)));
        assertThat(service.getOrder("REQ-MAIN").status()).isEqualTo(OrderStatus.ACKNOWLEDGED);

        OrderView done = service.receipt("REQ-MAIN", receipt("G2", "40.000", T0.plusSeconds(120)));
        assertThat(done.status()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(done.finishedAt()).isNotNull();
        assertThat(done.gates()).extracting(GateStepView::inPosition)
                .containsExactly(true, true);

        // 完成后释放闸门，同闸门可下达新令
        service.issue(twoGateOrder("REQ-NEXT", T0.plusSeconds(7200)));
        assertThat(repository.findOrder("REQ-NEXT")).isNotNull();
    }

    @Test
    void sameGateCannotHaveTwoActiveOrders() {
        service.issue(twoGateOrder("REQ-A", T0.plusSeconds(3600)));

        IssueOrderRequest overlapping = new IssueOrderRequest("REQ-B", List.of("G2"),
                List.of(new BigDecimal("10.000")), T0.plusSeconds(3600));
        assertThatThrownBy(() -> service.issue(overlapping))
                .isInstanceOf(DispatchException.class)
                .extracting(e -> ((DispatchException) e).code()).isEqualTo("GATE_BUSY");
        // 冲突不产生半成令
        assertThat(repository.findOrder("REQ-B")).isNull();
    }

    @Test
    void issueAndReceiptAreIdempotentAndConflictOnDifferentParams() {
        IssueOrderRequest request = twoGateOrder("REQ-IDEM", T0.plusSeconds(3600));
        OrderView first = service.issue(request);
        OrderView replayed = service.issue(request);
        assertThat(replayed.issuedAt()).isEqualTo(first.issuedAt());

        IssueOrderRequest changedDeadline = new IssueOrderRequest("REQ-IDEM", List.of("G1", "G2"),
                List.of(new BigDecimal("50.000"), new BigDecimal("40.000")),
                T0.plusSeconds(1800));
        assertThatThrownBy(() -> service.issue(changedDeadline))
                .isInstanceOf(DispatchException.class)
                .extracting(e -> ((DispatchException) e).code()).isEqualTo("IDEMPOTENCY_CONFLICT");
        // 同键异参不改变令状态
        assertThat(service.getOrder("REQ-IDEM").status()).isEqualTo(OrderStatus.ISSUED);

        service.acknowledge("REQ-IDEM");
        ReceiptRequest gateReceipt = receipt("G1", "50.500", T0.plusSeconds(30));
        service.receipt("REQ-IDEM", gateReceipt);
        // 同参回执重放返回首次结果
        OrderView replayView = service.receipt("REQ-IDEM", gateReceipt);
        assertThat(replayView.gates().get(0).actualOpening())
                .isEqualByComparingTo("50.500");
        // 异参回执冲突
        assertThatThrownBy(() -> service.receipt("REQ-IDEM", receipt("G1", "50.900", T0.plusSeconds(31))))
                .isInstanceOf(DispatchException.class)
                .extracting(e -> ((DispatchException) e).code()).isEqualTo("RECEIPT_CONFLICT");
    }

    @Test
    void deviationBeyondToleranceRaisesAlarm() {
        service.issue(new IssueOrderRequest("REQ-DEV", List.of("G1"),
                List.of(new BigDecimal("50.000")), T0.plusSeconds(3600)));
        service.acknowledge("REQ-DEV");
        // 实际 51.1，相对偏差 2.2% > 2%，判偏差但令仍可完成
        OrderView done = service.receipt("REQ-DEV", receipt("G1", "51.100", T0.plusSeconds(60)));
        assertThat(done.status()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(done.gates().get(0).inPosition()).isFalse();

        List<NotificationView> notifications = service.listNotifications("REQ-DEV");
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).type()).isEqualTo(NotificationType.DEVIATION_ALARM);
    }

    @Test
    void cancelBeforeAcknowledgeIsTerminalAndReleasesGates() {
        service.issue(twoGateOrder("REQ-CANCEL", T0.plusSeconds(3600)));
        OrderView cancelled = service.cancel("REQ-CANCEL");
        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.finishedAt()).isNotNull();

        // 已撤销令不可签收
        assertThatThrownBy(() -> service.acknowledge("REQ-CANCEL"))
                .isInstanceOf(DispatchException.class)
                .extracting(e -> ((DispatchException) e).code()).isEqualTo("ORDER_CANCELLED");

        // 撤销释放闸门，可重新下达
        service.issue(new IssueOrderRequest("REQ-CANCEL-2", List.of("G1"),
                List.of(new BigDecimal("30.000")), T0.plusSeconds(3600)));
        assertThat(repository.findOrder("REQ-CANCEL-2")).isNotNull();
    }

    @Test
    void acknowledgedOrderCannotCancelButCanAbortWithExecutedOpeningsKept() {
        service.issue(twoGateOrder("REQ-ABORT", T0.plusSeconds(3600)));
        service.acknowledge("REQ-ABORT");
        service.receipt("REQ-ABORT", receipt("G1", "49.500", T0.plusSeconds(60)));

        assertThatThrownBy(() -> service.cancel("REQ-ABORT"))
                .isInstanceOf(DispatchException.class)
                .extracting(e -> ((DispatchException) e).code()).isEqualTo("ALREADY_ACKNOWLEDGED");

        OrderView aborted = service.abort("REQ-ABORT", "上游来水骤减，人工中止");
        assertThat(aborted.status()).isEqualTo(OrderStatus.ABORTED);
        // 已执行闸门实际开度留痕
        GateStepView first = aborted.gates().get(0);
        assertThat(first.status().name()).isEqualTo("RECEIVED");
        assertThat(first.actualOpening()).isEqualByComparingTo("49.500");
        assertThat(aborted.gates().get(1).status().name()).isEqualTo("PENDING");
        assertThat(aborted.gates().get(1).actualOpening()).isNull();

        // 中止后不再接收回执
        assertThatThrownBy(() -> service.receipt("REQ-ABORT", receipt("G2", "40.000", T0.plusSeconds(90))))
                .isInstanceOf(DispatchException.class)
                .extracting(e -> ((DispatchException) e).code()).isEqualTo("ORDER_ABORTED");
    }

    @Test
    void timedOutOrderEscalatesNotifiesAndAllowsTakeover() {
        service.issue(twoGateOrder("REQ-TIMEOUT", T0.plusSeconds(600)));
        service.acknowledge("REQ-TIMEOUT");
        service.receipt("REQ-TIMEOUT", receipt("G1", "50.000", T0.plusSeconds(60)));

        // 未到时限不升级
        clock.setTime(T0.plusSeconds(599));
        assertThat(service.sweepTimeouts()).isZero();
        assertThat(service.getOrder("REQ-TIMEOUT").status()).isEqualTo(OrderStatus.ACKNOWLEDGED);

        // 超过执行时限：升级、通知上级、释放闸门
        clock.setTime(T0.plusSeconds(601));
        assertThat(service.sweepTimeouts()).isEqualTo(1);
        OrderView escalated = service.getOrder("REQ-TIMEOUT");
        assertThat(escalated.status()).isEqualTo(OrderStatus.TIMEOUT_ESCALATED);
        assertThat(escalated.escalated()).isTrue();
        assertThat(escalated.finishedAt()).isEqualTo(T0.plusSeconds(601));
        assertThat(service.listNotifications("REQ-TIMEOUT"))
                .extracting(NotificationView::type)
                .containsExactly(NotificationType.ESCALATION);

        // 升级后迟到回执不得改变终态
        assertThatThrownBy(() -> service.receipt("REQ-TIMEOUT",
                receipt("G2", "40.000", T0.plusSeconds(602))))
                .isInstanceOf(DispatchException.class)
                .extracting(e -> ((DispatchException) e).code()).isEqualTo("ORDER_ESCALATED");
        assertThat(service.getOrder("REQ-TIMEOUT").status())
                .isEqualTo(OrderStatus.TIMEOUT_ESCALATED);

        // 可被人工接管，且可幂等重复接管
        assertThat(service.takeOver("REQ-TIMEOUT").takenOver()).isTrue();
        assertThat(service.takeOver("REQ-TIMEOUT").takenOver()).isTrue();

        // 重复扫描结果稳定，不重复升级
        assertThat(service.sweepTimeouts()).isZero();
    }

    @Test
    void unacknowledgedOrderPastDeadlineAlsoEscalates() {
        service.issue(twoGateOrder("REQ-NOACK-TIMEOUT", T0.plusSeconds(600)));
        clock.setTime(T0.plusSeconds(601));
        assertThat(service.sweepTimeouts()).isEqualTo(1);
        OrderView escalated = service.getOrder("REQ-NOACK-TIMEOUT");
        assertThat(escalated.status()).isEqualTo(OrderStatus.TIMEOUT_ESCALATED);
        assertThat(escalated.acknowledgedAt()).isNull();
        assertThat(escalated.escalated()).isTrue();
        // 升级后签收不得翻转终态：该令从未签收成功，签收为竞态败方返回冲突
        assertThatThrownBy(() -> service.acknowledge("REQ-NOACK-TIMEOUT"))
                .isInstanceOf(DispatchException.class)
                .extracting(e -> ((DispatchException) e).code()).isEqualTo("ORDER_ESCALATED");
        assertThat(service.getOrder("REQ-NOACK-TIMEOUT").status())
                .isEqualTo(OrderStatus.TIMEOUT_ESCALATED);
        // 升级后撤销被拒
        assertThatThrownBy(() -> service.cancel("REQ-NOACK-TIMEOUT"))
                .isInstanceOf(DispatchException.class);
        // 可被人工接管
        assertThat(service.takeOver("REQ-NOACK-TIMEOUT").takenOver()).isTrue();
    }

    @Test
    void listAndRepeatedQueriesAreStableAndOrdered() {
        service.issue(new IssueOrderRequest("REQ-LIST-1", List.of("G1"),
                List.of(new BigDecimal("50.000")), T0.plusSeconds(3600)));
        service.issue(new IssueOrderRequest("REQ-LIST-2", List.of("G3"),
                List.of(new BigDecimal("20.000")), T0.plusSeconds(3600)));
        List<OrderView> first = service.listOrders();
        List<OrderView> second = service.listOrders();
        assertThat(first).usingRecursiveFieldByFieldElementComparator().isEqualTo(second);
        assertThat(first).extracting(OrderView::requestId)
                .containsExactly("REQ-LIST-1", "REQ-LIST-2");
    }
}
