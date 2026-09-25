package com.example.starter.dispatch;

import com.example.starter.dispatch.api.ApiException;
import com.example.starter.dispatch.api.dto.OrderView;
import com.example.starter.dispatch.domain.OrderStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.example.starter.dispatch.support.Requests.gate;
import static com.example.starter.dispatch.support.Requests.issue;
import static com.example.starter.dispatch.support.Requests.receipt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 超时升级：超过执行时限未收齐回执自动转 TIMEOUT，立即通知上级并释放闸门可接管；
 * 恰在时限时刻不判超时；超时后回执被拒绝。
 */
class TimeoutEscalationTest extends AbstractDispatchTest {

    private final Instant deadline = BASE.plusSeconds(60);

    @Test
    void order_not_timed_out_at_exact_deadline_moment() {
        service.issue(issue("REQ-TO-EDGE", deadline, gate("GATE-A", 30)));
        service.accept("REQ-TO-EDGE");
        clock.set(deadline); // 恰在执行时限时刻
        List<OrderView> escalated = service.scanTimeouts();
        assertThat(escalated).isEmpty();
        assertThat(service.get("REQ-TO-EDGE").status()).isEqualTo(OrderStatus.ACCEPTED);
        // 该时刻回执仍被接受并正常完成
        OrderView done = service.receipt("REQ-TO-EDGE", receipt("GATE-A", 30.0));
        assertThat(done.status()).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void expired_order_escalates_notifies_superior_and_releases_gates() {
        service.issue(issue("REQ-TO-1", deadline, gate("GATE-A", 30), gate("GATE-B", 40)));
        service.accept("REQ-TO-1");
        service.receipt("REQ-TO-1", receipt("GATE-A", 30.0));

        clock.set(deadline.plusMillis(1));
        List<OrderView> escalated = service.scanTimeouts();
        assertThat(escalated).hasSize(1);
        OrderView view = service.get("REQ-TO-1");
        assertThat(view.status()).isEqualTo(OrderStatus.TIMEOUT);
        assertThat(view.timeoutAt()).isEqualTo(deadline.plusMillis(1));
        assertThat(view.escalated()).isTrue();
        assertThat(view.alarms()).hasSize(1);
        assertThat(view.alarms().get(0).type()).isEqualTo("TIMEOUT_ESCALATION");
        assertThat(view.alarms().get(0).detail()).contains("1 孔闸门未回执");

        // 释放闸门，可被新的令接管
        Integer occupancies = jdbc.queryForObject("SELECT COUNT(*) FROM gate_occupancy", Integer.class);
        assertThat(occupancies).isZero();
        OrderView takeover = service.issue(issue("REQ-TO-1-TAKE", deadline.plusSeconds(300),
                gate("GATE-B", 45)));
        assertThat(takeover.status()).isEqualTo(OrderStatus.ISSUED);

        // 超时后迟到的第一门后续回执（第二门）被拒绝
        assertThatThrownBy(() -> service.receipt("REQ-TO-1", receipt("GATE-B", 40.0)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("ORDER_TIMEOUT");
    }

    @Test
    void repeat_scan_is_idempotent_and_does_not_duplicate_escalation() {
        service.issue(issue("REQ-TO-2", deadline, gate("GATE-A", 30)));
        service.accept("REQ-TO-2");
        clock.set(deadline.plusSeconds(10));
        assertThat(service.scanTimeouts()).hasSize(1);
        assertThat(service.scanTimeouts()).isEmpty();
        Integer alarms = jdbc.queryForObject(
                "SELECT COUNT(*) FROM alarm_event a JOIN dispatch_order o ON a.order_id = o.id "
                        + "WHERE o.request_id = 'REQ-TO-2'", Integer.class);
        assertThat(alarms).isEqualTo(1);
    }

    @Test
    void unaccepted_order_past_deadline_also_escalates() {
        service.issue(issue("REQ-TO-3", deadline, gate("GATE-A", 30)));
        clock.set(deadline.plusSeconds(1));
        service.scanTimeouts();
        assertThat(service.get("REQ-TO-3").status()).isEqualTo(OrderStatus.TIMEOUT);
        assertThatThrownBy(() -> service.accept("REQ-TO-3"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("ORDER_TIMEOUT");
    }

    @Test
    void receipt_arriving_after_deadline_escalates_instead_of_completing() {
        service.issue(issue("REQ-TO-4", deadline, gate("GATE-A", 30)));
        service.accept("REQ-TO-4");
        clock.set(deadline.plusSeconds(1));
        // 不经扫描器，直接在回执入口内复核时限：升级落库且回执被 409 拒绝
        assertThatThrownBy(() -> service.receipt("REQ-TO-4", receipt("GATE-A", 30.0)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("ORDER_TIMEOUT");
        OrderView view = service.get("REQ-TO-4");
        assertThat(view.status()).isEqualTo(OrderStatus.TIMEOUT);
        assertThat(view.escalated()).isTrue();
        assertThat(view.gates().get(0).actualOpening()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gate_occupancy", Integer.class)).isZero();
    }
}
