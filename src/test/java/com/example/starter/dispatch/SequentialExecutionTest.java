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
 * 顺序执行约束：多闸门令按令中顺序依次执行，前一门未回执完成不得启动下一门。
 */
class SequentialExecutionTest extends AbstractDispatchTest {

    private final Instant deadline = BASE.plusSeconds(120);

    @Test
    void cannot_receipt_later_gate_before_earlier_gate_completed() {
        service.issue(issue("REQ-SEQ-1", deadline, gate("GATE-A", 30), gate("GATE-B", 40)));
        service.accept("REQ-SEQ-1");

        assertThatThrownBy(() -> service.receipt("REQ-SEQ-1", receipt("GATE-B", 40.0)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("GATE-A")
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("GATE_ORDER_VIOLATION");

        // 被拒绝的回执不落库，第二门仍为空
        OrderView view = service.get("REQ-SEQ-1");
        assertThat(view.status()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(view.gates().get(1).actualOpening()).isNull();
    }

    @Test
    void next_gate_starts_after_previous_gate_receipted() {
        service.issue(issue("REQ-SEQ-2", deadline,
                gate("GATE-A", 10), gate("GATE-B", 20), gate("GATE-C", 30)));
        service.accept("REQ-SEQ-2");

        service.receipt("REQ-SEQ-2", receipt("GATE-A", 10.0));
        assertThatThrownBy(() -> service.receipt("REQ-SEQ-2", receipt("GATE-C", 30.0)))
                .isInstanceOf(ApiException.class);
        service.receipt("REQ-SEQ-2", receipt("GATE-B", 20.0));
        // 此时前两门均已完成，第三门允许执行
        OrderView done = service.receipt("REQ-SEQ-2", receipt("GATE-C", 30.0));
        assertThat(done.status()).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void receipt_before_accept_is_rejected() {
        service.issue(issue("REQ-SEQ-3", deadline, gate("GATE-A", 30)));
        assertThatThrownBy(() -> service.receipt("REQ-SEQ-3", receipt("GATE-A", 30.0)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("ORDER_NOT_ACCEPTED");
    }

    @Test
    void receipt_for_gate_not_in_order_is_rejected() {
        service.issue(issue("REQ-SEQ-4", deadline, gate("GATE-A", 30)));
        service.accept("REQ-SEQ-4");
        assertThatThrownBy(() -> service.receipt("REQ-SEQ-4", receipt("GATE-C", 30.0)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("GATE_NOT_IN_ORDER");
    }
}
