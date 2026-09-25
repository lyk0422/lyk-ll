package com.example.starter.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import com.example.starter.error.ApiException;
import com.example.starter.support.BaseIntegrationTest;
import com.example.starter.telemetry.TelemetryService.TelemetryRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 遥信测试：按间隔+时标去重；实际状态与该步预期不符即中止操作并转异常。
 */
class TelemetryTest extends BaseIntegrationTest {

    @Autowired
    private TelemetryService telemetryService;

    @Test
    void 遥信按间隔加时标去重() {
        var req = new TelemetryRequest("INT-1101", "BR-1101", "OFF",
                Instant.parse("2026-09-26T01:00:00Z"));
        var first = telemetryService.report(req);
        var dup = telemetryService.report(req);

        assertThat(first.deduped()).isFalse();
        assertThat(dup.deduped()).isTrue();
        assertThat(dup.eventId()).isEqualTo(first.eventId());
        assertThat(count("telemetry_event", "1 = 1")).isEqualTo(1);
    }

    @Test
    void 遥信状态与步骤预期不符_中止操作并转异常() {
        long orderId = draftReviewIssue("tm-abort", "INT-1101",
                step(1, "BR-1101", "OPEN_BREAKER", "OFF"),
                step(2, "DIS-1101", "OPEN_DISCONNECTOR", "OPEN"));
        confirm(orderId, 1, "tm-abort-c1", "操作人A", "监护人B");

        // 实际回报 ON，与该步预期 OFF 不符
        var view = telemetryService.report(new TelemetryRequest(
                "INT-1101", "BR-1101", "ON", Instant.parse("2026-09-26T01:00:00Z")));
        assertThat(view.orderAborted()).isTrue();
        assertThat(view.late()).isFalse();
        assertThat(orderStatus(orderId)).isEqualTo("ABORTED");
        assertThat(count("order_event", "order_id = ? AND event_type = 'ABORTED'", orderId))
                .isEqualTo(1);
        // 中止后间隔槽位释放
        assertThat(count("switching_order", "executing_interval = 'INT-1101'")).isZero();
        // 异常票禁止继续步确认
        assertThatThrownBy(() -> confirm(orderId, 2, "tm-abort-c2", "操作人A", "监护人B"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void 遥信状态与步骤预期一致_不中止() {
        long orderId = draftReviewIssue("tm-ok", "INT-1101",
                step(1, "BR-1101", "OPEN_BREAKER", "OFF"));
        confirm(orderId, 1, "tm-ok-c1", "操作人A", "监护人B");

        var view = telemetryService.report(new TelemetryRequest(
                "INT-1101", "BR-1101", "OFF", Instant.parse("2026-09-26T01:00:00Z")));
        assertThat(view.orderAborted()).isFalse();
        assertThat(orderStatus(orderId)).isEqualTo("EXECUTING");
    }

    @Test
    void 无执行中操作票时记为迟到事件() {
        var view = telemetryService.report(new TelemetryRequest(
                "INT-1102", "BR-1102", "OFF", Instant.parse("2026-09-26T01:00:00Z")));
        assertThat(view.late()).isTrue();
        assertThat(view.orderAborted()).isFalse();
        assertThat(count("telemetry_event", "late = TRUE")).isEqualTo(1);
    }
}
