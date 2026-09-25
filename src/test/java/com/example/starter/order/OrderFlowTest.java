package com.example.starter.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import com.example.starter.error.ApiException;
import com.example.starter.support.BaseIntegrationTest;
import com.example.starter.telemetry.TelemetryService;
import com.example.starter.telemetry.TelemetryService.TelemetryRequest;
import com.example.starter.telemetry.TelemetryService.TelemetryView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 主流程测试：拟票→审核→预发→逐项执行→归档，归档终态与迟到遥信。
 */
class OrderFlowTest extends BaseIntegrationTest {

    @Autowired
    private TelemetryService telemetryService;

    @Test
    void 主流程_拟票审核预发逐项执行归档() {
        long orderId = draft("flow-draft", "INT-1101",
                step(1, "BR-1101", "OPEN_BREAKER", "OFF"),
                step(2, "DIS-1101", "OPEN_DISCONNECTOR", "OPEN"));
        assertThat(orderStatus(orderId)).isEqualTo("DRAFT");

        review(orderId);
        assertThat(orderStatus(orderId)).isEqualTo("REVIEWED");

        assertThat(issue(orderId, "flow-issue")).isEqualTo(200);
        assertThat(orderStatus(orderId)).isEqualTo("EXECUTING");

        assertThat(confirm(orderId, 1, "flow-c1", "操作人A", "监护人B").getStatusCode().value())
                .isEqualTo(200);
        assertThat(stepStatus(orderId, 1)).isEqualTo("DONE");
        assertThat(deviceState("BR-1101")).isEqualTo("OFF");

        assertThat(confirm(orderId, 2, "flow-c2", "操作人A", "监护人B").getStatusCode().value())
                .isEqualTo(200);
        assertThat(deviceState("DIS-1101")).isEqualTo("OPEN");

        var archived = orderService.archive(orderId);
        assertThat(archived.status()).isEqualTo("ARCHIVED");
        // 归档后间隔槽位释放
        assertThat(count("switching_order",
                "executing_interval = 'INT-1101'")).isZero();
    }

    @Test
    void 归档为终态_重复归档幂等成功_归档后禁止步确认() {
        long orderId = draftReviewIssue("term", "INT-1101",
                step(1, "BR-1101", "OPEN_BREAKER", "OFF"));
        confirm(orderId, 1, "term-c1", "操作人A", "监护人B");

        var first = orderService.archive(orderId);
        var second = orderService.archive(orderId);
        assertThat(first.status()).isEqualTo("ARCHIVED");
        assertThat(second.status()).isEqualTo("ARCHIVED");
        assertThat(second.issuedAt()).isEqualTo(first.issuedAt());

        assertThatThrownBy(() -> confirm(orderId, 1, "term-c2", "操作人A", "监护人B"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void 归档后迟到遥信只记迟到事件() {
        long orderId = draftReviewIssue("late", "INT-1101",
                step(1, "BR-1101", "OPEN_BREAKER", "OFF"));
        confirm(orderId, 1, "late-c1", "操作人A", "监护人B");
        orderService.archive(orderId);

        TelemetryView view = telemetryService.report(new TelemetryRequest(
                "INT-1101", "BR-1101", "ON", Instant.parse("2026-09-26T01:00:00Z")));
        assertThat(view.late()).isTrue();
        assertThat(view.orderAborted()).isFalse();
        assertThat(view.deduped()).isFalse();
        assertThat(count("telemetry_event", "late = TRUE")).isEqualTo(1);
        // 归档终态不受迟到遥信影响
        assertThat(orderStatus(orderId)).isEqualTo("ARCHIVED");
    }

    @Test
    void 步序轨迹与事件可复算() {
        long orderId = draftReviewIssue("trace", "INT-1101",
                step(1, "BR-1101", "OPEN_BREAKER", "OFF"));
        confirm(orderId, 1, "trace-c1", "操作人A", "监护人B");
        orderService.archive(orderId);

        var trace = orderService.trace(orderId);
        assertThat(trace.events()).extracting("eventType")
                .containsExactly("DRAFTED", "REVIEWED", "ISSUED", "STEP_DONE", "ARCHIVED");
        assertThat(trace.interlockHits()).isEmpty();
        assertThat(trace.unlocks()).isEmpty();
    }

    @Test
    void 未执行完毕不允许归档() {
        long orderId = draftReviewIssue("pending", "INT-1101",
                step(1, "BR-1101", "OPEN_BREAKER", "OFF"),
                step(2, "DIS-1101", "OPEN_DISCONNECTOR", "OPEN"));
        confirm(orderId, 1, "pending-c1", "操作人A", "监护人B");

        assertThatThrownBy(() -> orderService.archive(orderId))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("STEPS_PENDING");
    }
}
