package com.example.starter.dispatch;

import com.example.starter.dispatch.api.ApiException;
import com.example.starter.dispatch.api.dto.IssueOrderRequest;
import com.example.starter.dispatch.api.dto.OrderView;
import com.example.starter.dispatch.domain.OrderStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.example.starter.dispatch.support.Requests.gate;
import static com.example.starter.dispatch.support.Requests.issue;
import static com.example.starter.dispatch.support.Requests.receipt;
import static com.example.starter.dispatch.support.Requests.withDeadline;
import static com.example.starter.dispatch.support.Requests.withExtraGate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 幂等边界：下达/签收/回执以 requestId 幂等，指纹覆盖 requestId 与全部业务字段；
 * 同键同参返回首次结果，同键异参 409 且不改变令状态。
 */
class IdempotencyTest extends AbstractDispatchTest {

    private final Instant deadline = BASE.plusSeconds(180);

    @Test
    void replay_same_issue_returns_first_result_without_creating_duplicate() {
        IssueOrderRequest request = issue("REQ-IDEM-1", deadline, gate("GATE-A", 30));
        OrderView first = service.issue(request);
        OrderView replay = service.issue(request);
        assertThat(replay).usingRecursiveComparison().isEqualTo(first);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dispatch_order WHERE request_id = 'REQ-IDEM-1'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void same_request_id_with_different_gates_returns_409_and_keeps_state() {
        IssueOrderRequest request = issue("REQ-IDEM-2", deadline, gate("GATE-A", 30));
        service.issue(request);
        service.accept("REQ-IDEM-2");

        IssueOrderRequest tampered = withExtraGate(request, gate("GATE-B", 40));
        assertThatThrownBy(() -> service.issue(tampered))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("IDEMPOTENT_PARAM_MISMATCH");

        // 状态与闸门序列均未改变
        OrderView view = service.get("REQ-IDEM-2");
        assertThat(view.status()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(view.gates()).hasSize(1);
    }

    @Test
    void same_request_id_with_different_deadline_returns_409() {
        IssueOrderRequest request = issue("REQ-IDEM-3", deadline, gate("GATE-A", 30));
        service.issue(request);
        assertThatThrownBy(() -> service.issue(withDeadline(request, deadline.plusSeconds(10))))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("IDEMPOTENT_PARAM_MISMATCH");
    }

    @Test
    void same_request_id_with_different_target_opening_returns_409() {
        IssueOrderRequest request = issue("REQ-IDEM-4", deadline, gate("GATE-A", 30));
        service.issue(request);
        IssueOrderRequest changed = issue("REQ-IDEM-4", deadline, gate("GATE-A", 31));
        assertThatThrownBy(() -> service.issue(changed))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("IDEMPOTENT_PARAM_MISMATCH");
    }

    @Test
    void duplicate_accept_is_idempotent() {
        service.issue(issue("REQ-IDEM-5", deadline, gate("GATE-A", 30)));
        OrderView first = service.accept("REQ-IDEM-5");
        clock.advanceSeconds(10);
        OrderView again = service.accept("REQ-IDEM-5");
        assertThat(again.status()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(again.acceptedAt()).isEqualTo(first.acceptedAt());
    }

    @Test
    void duplicate_receipt_same_param_replays_first_result_and_different_param_conflicts() {
        service.issue(issue("REQ-IDEM-6", deadline, gate("GATE-A", 50)));
        service.accept("REQ-IDEM-6");
        OrderView first = service.receipt("REQ-IDEM-6", receipt("GATE-A", 50.0));
        assertThat(first.status()).isEqualTo(OrderStatus.COMPLETED);

        clock.advanceSeconds(10);
        OrderView replay = service.receipt("REQ-IDEM-6", receipt("GATE-A", 50.0));
        assertThat(replay).usingRecursiveComparison().isEqualTo(first);

        assertThatThrownBy(() -> service.receipt("REQ-IDEM-6", receipt("GATE-A", 50.5)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("IDEMPOTENT_PARAM_MISMATCH");

        Integer receiptCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM gate_receipt r JOIN dispatch_order o ON r.order_id = o.id "
                        + "WHERE o.request_id = 'REQ-IDEM-6'", Integer.class);
        assertThat(receiptCount).isEqualTo(1);
    }
}
