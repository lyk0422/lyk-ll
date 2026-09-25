package com.example.starter.dispatch;

import com.example.starter.dispatch.api.ApiException;
import com.example.starter.dispatch.api.dto.OrderView;
import com.example.starter.dispatch.domain.OrderStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.example.starter.dispatch.support.Requests.gate;
import static com.example.starter.dispatch.support.Requests.issue;
import static com.example.starter.dispatch.support.Requests.receipt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 撤销（签收前，终态）与中止（签收后，留痕）规则。
 */
class CancelAbortTest extends AbstractDispatchTest {

    private final Instant deadline = BASE.plusSeconds(300);

    @Test
    void cancel_before_accept_is_terminal_releases_gate_and_is_idempotent() {
        service.issue(issue("REQ-CA-1", deadline, gate("GATE-A", 30)));
        OrderView cancelled = service.cancel("REQ-CA-1");
        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.cancelledAt()).isEqualTo(BASE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gate_occupancy", Integer.class)).isZero();

        // 撤销幂等；已签收/超时类动作被拒绝
        assertThat(service.cancel("REQ-CA-1").status()).isEqualTo(OrderStatus.CANCELLED);
        assertThatThrownBy(() -> service.accept("REQ-CA-1"))
                .isInstanceOf(ApiException.class).extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("ORDER_CANCELLED");

        // 闸门释放后可被新令使用
        assertThat(service.issue(issue("REQ-CA-1-NEXT", deadline, gate("GATE-A", 60))).status())
                .isEqualTo(OrderStatus.ISSUED);
    }

    @Test
    void cancel_after_accept_is_rejected_abort_instead() {
        service.issue(issue("REQ-CA-2", deadline, gate("GATE-A", 30)));
        service.accept("REQ-CA-2");
        assertThatThrownBy(() -> service.cancel("REQ-CA-2"))
                .isInstanceOf(ApiException.class).extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("ALREADY_ACCEPTED");
        assertThat(service.get("REQ-CA-2").status()).isEqualTo(OrderStatus.ACCEPTED);
    }

    @Test
    void abort_before_accept_is_rejected_cancel_instead() {
        service.issue(issue("REQ-CA-3", deadline, gate("GATE-A", 30)));
        assertThatThrownBy(() -> service.abort("REQ-CA-3"))
                .isInstanceOf(ApiException.class).extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("ORDER_NOT_ACCEPTED");
    }

    @Test
    void abort_after_partial_execution_records_actual_openings_of_executed_gates() {
        service.issue(issue("REQ-CA-4", deadline, gate("GATE-A", 30), gate("GATE-B", 40)));
        service.accept("REQ-CA-4");
        service.receipt("REQ-CA-4", receipt("GATE-A", 29.5)); // 已执行第一门
        clock.advanceSeconds(7);

        OrderView aborted = service.abort("REQ-CA-4");
        assertThat(aborted.status()).isEqualTo(OrderStatus.ABORTED);
        assertThat(aborted.abortedAt()).isEqualTo(BASE.plusSeconds(7));
        assertThat(aborted.abortedDetail()).contains("GATE-A").contains("29.500").doesNotContain("GATE-B");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gate_occupancy", Integer.class)).isZero();

        // 中止后回执被拒绝；中止幂等返回首次留痕
        assertThatThrownBy(() -> service.receipt("REQ-CA-4", receipt("GATE-B", 40.0)))
                .isInstanceOf(ApiException.class).extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("ORDER_ABORTED");
        OrderView again = service.abort("REQ-CA-4");
        assertThat(again.abortedDetail()).isEqualTo(aborted.abortedDetail());
    }

    @Test
    void abort_after_completion_is_rejected() {
        service.issue(issue("REQ-CA-5", deadline, gate("GATE-A", 30)));
        service.accept("REQ-CA-5");
        service.receipt("REQ-CA-5", receipt("GATE-A", 30.0));
        assertThatThrownBy(() -> service.abort("REQ-CA-5"))
                .isInstanceOf(ApiException.class).extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("ORDER_COMPLETED");
    }

    @Test
    void unknown_order_returns_404() {
        assertThatThrownBy(() -> service.get("REQ-NONE"))
                .isInstanceOf(ApiException.class).extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("ORDER_NOT_FOUND");
    }

    @Test
    void invalid_openings_and_unknown_gates_are_rejected() {
        assertThatThrownBy(() -> service.issue(issue("REQ-CA-6", deadline, gate("GATE-A", 120))))
                .isInstanceOf(ApiException.class).extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("INVALID_OPENING");
        assertThatThrownBy(() -> service.issue(issue("REQ-CA-7", deadline, gate("GATE-X", 10))))
                .isInstanceOf(ApiException.class).extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("GATE_NOT_FOUND");
        // 被拒令不残留
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_order", Integer.class)).isZero();
    }
}
