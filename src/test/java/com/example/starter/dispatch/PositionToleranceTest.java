package com.example.starter.dispatch;

import com.example.starter.dispatch.api.dto.OrderView;
import com.example.starter.dispatch.domain.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static com.example.starter.dispatch.support.Requests.gate;
import static com.example.starter.dispatch.support.Requests.issue;
import static com.example.starter.dispatch.support.Requests.receipt;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 到位判定边界：实际开度与目标偏差不超过 ±2%（含边界）判到位；超限判偏差并自动告警。
 */
class PositionToleranceTest extends AbstractDispatchTest {

    private final Instant deadline = BASE.plusSeconds(180);

    // 目标 50%：容差恰为 ±1.00 个百分点
    @ParameterizedTest(name = "目标50% 实际{0}% => 到位={1} 告警={2}")
    @CsvSource({
            "50.00, true,  false",
            "49.00, true,  false",  // 恰在 -2% 边界
            "51.00, true,  false",  // 恰在 +2% 边界
            "48.99, false, true",
            "51.01, false, true"
    })
    void two_percent_boundary_is_inclusive(double actual, boolean expectedInPosition, boolean expectedAlarm) {
        String requestId = "REQ-TOL-" + actual;
        service.issue(issue(requestId, deadline, gate("GATE-A", 50)));
        service.accept(requestId);
        OrderView view = service.receipt(requestId, receipt("GATE-A", actual));

        OrderView.GateReceiptView gate = view.gates().get(0);
        assertThat(gate.inPosition()).isEqualTo(expectedInPosition);
        assertThat(gate.alarmed()).isEqualTo(expectedAlarm);
        if (expectedAlarm) {
            assertThat(view.status()).isEqualTo(OrderStatus.COMPLETED); // 偏差仅告警，不阻断完成
            assertThat(view.alarms()).hasSize(1);
            assertThat(view.alarms().get(0).type()).isEqualTo("GATE_DEVIATION");
            assertThat(view.alarms().get(0).gateCode()).isEqualTo("GATE-A");
        } else {
            assertThat(view.alarms()).isEmpty();
        }
    }

    @Test
    void zero_target_uses_two_percentage_points_tolerance_and_boundary_is_inclusive() {
        String requestId = "REQ-TOL-ZERO";
        service.issue(issue(requestId, deadline, gate("GATE-A", 0)));
        service.accept(requestId);
        // 恰在 +2 个百分点边界：到位
        OrderView view = service.receipt(requestId, receipt("GATE-A", 2.0));
        assertThat(view.gates().get(0).inPosition()).isTrue();
        assertThat(view.alarms()).isEmpty();
    }

    @Test
    void zero_target_beyond_boundary_raises_deviation_alarm() {
        String requestId = "REQ-TOL-ZERO-2";
        service.issue(issue(requestId, deadline, gate("GATE-A", 0)));
        service.accept(requestId);
        OrderView view = service.receipt(requestId, receipt("GATE-A", 2.01));
        assertThat(view.gates().get(0).inPosition()).isFalse();
        assertThat(view.alarms()).hasSize(1);
        assertThat(view.alarms().get(0).detail()).contains("超过±2%");
    }

    @Test
    void deviation_percentage_is_recorded() {
        String requestId = "REQ-TOL-DEV";
        service.issue(issue(requestId, deadline, gate("GATE-A", 80)));
        service.accept(requestId);
        OrderView view = service.receipt(requestId, receipt("GATE-A", 76));
        // (76-80)/80*100 = -5.0000%
        assertThat(view.gates().get(0).deviationPct()).isEqualByComparingTo("-5.0000");
        assertThat(view.gates().get(0).inPosition()).isFalse();
        assertThat(view.gates().get(0).alarmed()).isTrue();
    }
}
