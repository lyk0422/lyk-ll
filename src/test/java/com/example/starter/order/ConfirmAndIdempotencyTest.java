package com.example.starter.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.example.starter.error.ApiException;
import com.example.starter.order.OrderDtos.ConfirmRequest;
import com.example.starter.order.OrderDtos.DraftRequest;
import com.example.starter.order.OrderDtos.IssueRequest;
import com.example.starter.support.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 双人确认与幂等测试：单人操作不生效；拟票、预发、步确认按 requestId 幂等，
 * 同键同参返回首次结果，同键异参返回 409。
 */
class ConfirmAndIdempotencyTest extends BaseIntegrationTest {

    @Test
    void 单人操作不生效_须操作人监护人双确认() {
        long orderId = draftReviewIssue("dual", "INT-1101",
                step(1, "BR-1101", "OPEN_BREAKER", "OFF"));

        // 操作人与监护人同一人：不生效
        assertThatThrownBy(() -> confirm(orderId, 1, "dual-c1", "操作人A", "操作人A"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("DUAL_CONFIRM_REQUIRED");
        // 监护人缺失：不生效
        assertThatThrownBy(() -> confirm(orderId, 1, "dual-c2", "操作人A", null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("DUAL_CONFIRM_REQUIRED");

        assertThat(stepStatus(orderId, 1)).isEqualTo("PENDING");
        assertThat(deviceState("BR-1101")).isEqualTo("ON");

        // 双人确认：生效
        assertThat(confirm(orderId, 1, "dual-c3", "操作人A", "监护人B")
                .getStatusCode().value()).isEqualTo(200);
        assertThat(stepStatus(orderId, 1)).isEqualTo("DONE");
        assertThat(deviceState("BR-1101")).isEqualTo("OFF");
    }

    @Test
    void 拟票幂等_同键同参返回首次结果_同键异参409() {
        var steps = List.of(step(1, "BR-1101", "OPEN_BREAKER", "OFF"));
        var first = orderService.draft(new DraftRequest("idem-draft", "INT-1101",
                "票一", "拟票人甲", steps));
        var replay = orderService.draft(new DraftRequest("idem-draft", "INT-1101",
                "票一", "拟票人甲", steps));
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(count("switching_order", "1 = 1")).isEqualTo(1);

        // 同键异参：409
        assertThatThrownBy(() -> orderService.draft(new DraftRequest("idem-draft", "INT-1101",
                "票二", "拟票人甲", steps)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(count("switching_order", "1 = 1")).isEqualTo(1);
    }

    @Test
    void 预发幂等_同键重放返回首次结果() {
        long orderId = draft("idem-issue-draft", "INT-1101",
                step(1, "BR-1101", "OPEN_BREAKER", "OFF"));
        review(orderId);

        var first = orderService.issue(orderId, new IssueRequest("idem-issue"));
        var replay = orderService.issue(orderId, new IssueRequest("idem-issue"));
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(count("order_event", "order_id = ? AND event_type = 'ISSUED'", orderId))
                .isEqualTo(1);

        // 同键异参（不同操作票）：409
        assertThatThrownBy(() -> orderService.issue(orderId + 100, new IssueRequest("idem-issue")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void 步确认幂等_同键重放返回首次结果_同键异参409() {
        long orderId = draftReviewIssue("idem-confirm", "INT-1101",
                step(1, "BR-1101", "OPEN_BREAKER", "OFF"));

        var first = confirm(orderId, 1, "idem-c1", "操作人A", "监护人B");
        var replay = confirm(orderId, 1, "idem-c1", "操作人A", "监护人B");
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(count("order_event", "order_id = ? AND event_type = 'STEP_DONE'", orderId))
                .isEqualTo(1);

        // 同键异参（不同监护人）：409
        assertThatThrownBy(() -> confirm(orderId, 1, "idem-c1", "操作人A", "监护人C"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void 跳步执行被拒绝_须按步序逐项执行() {
        long orderId = draftReviewIssue("seq", "INT-1101",
                step(1, "BR-1101", "OPEN_BREAKER", "OFF"),
                step(2, "DIS-1101", "OPEN_DISCONNECTOR", "OPEN"));

        assertThatThrownBy(() -> confirm(orderId, 2, "seq-c2", "操作人A", "监护人B"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("STEP_SEQUENCE");
        assertThat(stepStatus(orderId, 2)).isEqualTo("PENDING");
    }
}
