package com.example.starter.dispatch;

import com.example.starter.dispatch.api.dto.OrderView;
import com.example.starter.dispatch.domain.OrderStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static com.example.starter.dispatch.support.Requests.gate;
import static com.example.starter.dispatch.support.Requests.issue;
import static com.example.starter.dispatch.support.Requests.receipt;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 主流程：下达 → 签收 → 逐门执行 → 回执（实际开度与完成时刻）→ 全部完成，
 * 并校验查询稳定、按序可查、到位后闸门主数据开度同步。
 */
class DispatchFlowTest extends AbstractDispatchTest {

    private final Instant deadline = BASE.plusSeconds(60);

    @Test
    void issue_accept_receipts_in_sequence_completes_order() {
        OrderView issued = service.issue(issue("REQ-FLOW-1", deadline, gate("GATE-A", 30), gate("GATE-B", 40)));
        assertThat(issued.status()).isEqualTo(OrderStatus.ISSUED);
        assertThat(issued.gates()).hasSize(2);
        assertThat(issued.gates().get(0).actualOpening()).isNull();

        OrderView accepted = service.accept("REQ-FLOW-1");
        assertThat(accepted.status()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(accepted.acceptedAt()).isEqualTo(BASE);

        // 第一门回执后令仍在执行，第二门尚未回执
        OrderView first = service.receipt("REQ-FLOW-1", receipt("GATE-A", 30.0));
        assertThat(first.status()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(first.gates().get(0).inPosition()).isTrue();
        assertThat(first.gates().get(0).receivedAt()).isEqualTo(BASE);
        assertThat(first.gates().get(1).actualOpening()).isNull();

        clock.advanceSeconds(5);
        OrderView done = service.receipt("REQ-FLOW-1", receipt("GATE-B", 40.0));
        assertThat(done.status()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(done.completedAt()).isEqualTo(BASE.plusSeconds(5));
        assertThat(done.gates()).extracting(OrderView.GateReceiptView::gateCode)
                .containsExactly("GATE-A", "GATE-B");
        assertThat(done.alarms()).isEmpty();

        // 终态释放闸门占用，并把实际开度同步到闸门主数据
        Integer occupancies = jdbc.queryForObject(
                "SELECT COUNT(*) FROM gate_occupancy", Integer.class);
        assertThat(occupancies).isZero();
        BigDecimal gateBOpening = jdbc.queryForObject(
                "SELECT opening FROM gate WHERE code = 'GATE-B'", BigDecimal.class);
        assertThat(gateBOpening).isEqualByComparingTo("40.000");
    }

    @Test
    void repeated_queries_are_stable_and_list_is_ordered_by_issue_sequence() {
        service.issue(issue("REQ-LIST-1", deadline, gate("GATE-A", 10)));
        clock.advanceSeconds(1);
        service.issue(issue("REQ-LIST-2", deadline, gate("GATE-B", 20)));
        clock.advanceSeconds(1);
        service.issue(issue("REQ-LIST-3", deadline, gate("GATE-C", 30)));

        List<OrderView> first = service.list();
        List<OrderView> second = service.list();
        assertThat(first).usingRecursiveComparison().isEqualTo(second);
        assertThat(first).extracting(OrderView::requestId)
                .containsExactly("REQ-LIST-1", "REQ-LIST-2", "REQ-LIST-3");

        OrderView one = service.get("REQ-LIST-2");
        OrderView oneAgain = service.get("REQ-LIST-2");
        assertThat(one).usingRecursiveComparison().isEqualTo(oneAgain);
    }
}
