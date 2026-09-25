package com.example.starter.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import com.example.starter.support.BaseIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * 24 小时有效期测试：自预发起 24 小时内（UTC）有效，恰满 24 小时作废且已执行步骤保留。
 */
class OrderExpiryTest extends BaseIntegrationTest {

    @Test
    void 恰满24小时作废_已执行步骤保留() {
        long orderId = draftReviewIssue("expiry", "INT-1101",
                step(1, "BR-1101", "OPEN_BREAKER", "OFF"),
                step(2, "DIS-1101", "OPEN_DISCONNECTOR", "OPEN"));

        // 23:59:59 仍有效
        clock.advance(Duration.ofHours(23).plusMinutes(59).plusSeconds(59));
        assertThat(confirm(orderId, 1, "expiry-c1", "操作人A", "监护人B")
                .getStatusCode().value()).isEqualTo(200);
        assertThat(stepStatus(orderId, 1)).isEqualTo("DONE");

        // 恰满 24 小时：作废
        clock.advance(Duration.ofSeconds(1));
        var response = confirm(orderId, 2, "expiry-c2", "操作人A", "监护人B");
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).contains("ORDER_EXPIRED");

        assertThat(orderStatus(orderId)).isEqualTo("EXPIRED");
        // 已执行步骤保留，未执行步骤不补刀
        assertThat(stepStatus(orderId, 1)).isEqualTo("DONE");
        assertThat(stepStatus(orderId, 2)).isEqualTo("PENDING");
        assertThat(deviceState("BR-1101")).isEqualTo("OFF");
        // 间隔槽位释放，同间隔可开新票
        assertThat(count("switching_order", "executing_interval = 'INT-1101'")).isZero();
        long next = draftReviewIssue("expiry-next", "INT-1101",
                step(1, "BR-1102", "OPEN_BREAKER", "OFF"));
        assertThat(orderStatus(next)).isEqualTo("EXECUTING");
        // 作废事件留痕
        assertThat(count("order_event", "order_id = ? AND event_type = 'EXPIRED'", orderId))
                .isEqualTo(1);
    }

    @Test
    void 作废后再次确认返回409且幂等留痕() {
        long orderId = draftReviewIssue("expiry2", "INT-1101",
                step(1, "BR-1101", "OPEN_BREAKER", "OFF"));
        clock.advance(Duration.ofHours(24));

        var first = confirm(orderId, 1, "expiry2-c1", "操作人A", "监护人B");
        var replay = confirm(orderId, 1, "expiry2-c1", "操作人A", "监护人B");
        assertThat(first.getStatusCode().value()).isEqualTo(409);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(count("order_event", "order_id = ? AND event_type = 'EXPIRED'", orderId))
                .isEqualTo(1);
    }
}
