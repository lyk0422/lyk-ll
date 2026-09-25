package com.example.starter.crane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.crane.AbstractCraneIntegrationTest;
import com.example.starter.crane.ConflictException;
import com.example.starter.crane.NotFoundException;
import com.example.starter.crane.api.AuthorizationRequest;
import com.example.starter.crane.api.AuthorizationView;
import com.example.starter.crane.api.ReleaseRequest;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 授权主流程与失败分支：半开区间冲突、端点相接、超载拒绝、requestId 幂等、释放。
 */
class AuthorizationServiceTest extends AbstractCraneIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-25T09:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-25T09:10:00Z");
    private static final Instant T2 = Instant.parse("2026-09-25T09:20:00Z");

    @Autowired
    private AuthorizationService authorizationService;

    private AuthorizationRequest request(String requestId, String crane, String zone,
            Instant start, Instant end, String loadKg) {
        return new AuthorizationRequest(requestId, crane, zone, start, end, new BigDecimal(loadKg));
    }

    @Test
    void grant_success_and_persist() {
        AuthorizationView view = authorizationService.apply(
                request("REQ-1", "C-101", "Z-A1", T0, T1, "4000"));

        assertThat(view.result()).isEqualTo("GRANTED");
        assertThat(view.status()).isEqualTo("GRANTED");
        assertThat(view.replayed()).isFalse();
        assertThat(view.rejectReason()).isNull();

        AuthorizationView fetched = authorizationService.get("REQ-1");
        assertThat(fetched.result()).isEqualTo("GRANTED");
        assertThat(fetched.startTime()).isEqualTo(T0);
        assertThat(fetched.endTime()).isEqualTo(T1);
    }

    @Test
    void idempotent_same_key_same_params_returns_first_result() {
        AuthorizationRequest req = request("REQ-2", "C-101", "Z-A1", T0, T1, "4000");
        AuthorizationView first = authorizationService.apply(req);
        AuthorizationView replay = authorizationService.apply(req);

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.result()).isEqualTo("GRANTED");
        // 数值等价写法（尾零差异）视为同参。
        AuthorizationRequest sameNumeric = request("REQ-2", "C-101", "Z-A1", T0, T1, "4000.00");
        assertThat(authorizationService.apply(sameNumeric).replayed()).isTrue();

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM zone_authorization WHERE request_id = 'REQ-2'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void same_key_different_params_conflict_409_and_does_not_occupy() {
        authorizationService.apply(request("REQ-3", "C-101", "Z-A1", T0, T1, "4000"));

        // 异参（吊重不同）→ 409
        assertThatThrownBy(() ->
                authorizationService.apply(request("REQ-3", "C-101", "Z-A1", T0, T1, "4500")))
                .isInstanceOf(ConflictException.class);

        // 异参未落库：原结论仍可幂等回放
        assertThat(authorizationService.get("REQ-3").loadKg()).isEqualByComparingTo("4000");

        // 409 不占用区域：原合法授权释放后，另一塔机在相同时段即可获批
        authorizationService.release("REQ-3", new ReleaseRequest("CANCELLED"));
        AuthorizationView other = authorizationService.apply(
                request("REQ-3B", "C-102", "Z-A1", T0, T1, "2000"));
        assertThat(other.result()).isEqualTo("GRANTED");
    }

    @Test
    void half_open_interval_overlap_rejected_but_endpoint_touch_allowed() {
        authorizationService.apply(request("REQ-4", "C-101", "Z-A1", T0, T1, "1000"));

        // 区间重叠 → 409
        assertThatThrownBy(() -> authorizationService.apply(
                request("REQ-5", "C-102", "Z-A1",
                        T0.plusSeconds(1), T1.plusSeconds(1), "1000")))
                .isInstanceOf(ConflictException.class);

        // 端点相接：[T1, T2) 与 [T0, T1) 不冲突 → 允许
        AuthorizationView touching = authorizationService.apply(
                request("REQ-6", "C-102", "Z-A1", T1, T2, "1000"));
        assertThat(touching.result()).isEqualTo("GRANTED");

        // 完全包含同样冲突
        assertThatThrownBy(() -> authorizationService.apply(
                request("REQ-7", "C-102", "Z-A1",
                        T0.plusSeconds(60), T1.minusSeconds(60), "1000")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void overload_rejected_and_does_not_occupy_zone() {
        // C-102 额定 3000kg，申请 3001kg → 拒绝
        AuthorizationView overloaded = authorizationService.apply(
                request("REQ-8", "C-102", "Z-A1", T0, T1, "3001"));
        assertThat(overloaded.result()).isEqualTo("REJECTED_OVERLOAD");
        assertThat(overloaded.status()).isEqualTo("REJECTED");
        assertThat(overloaded.rejectReason()).contains("3001").contains("3000");

        // 超载拒绝不占区：同区同时段另一塔机可获批
        AuthorizationView other = authorizationService.apply(
                request("REQ-10", "C-101", "Z-A1", T0, T1, "1000"));
        assertThat(other.result()).isEqualTo("GRANTED");

        // 恰等于额定载荷 → 批准（超额定才拒绝）；换端点相接时段避免与 REQ-10 冲突
        AuthorizationView exact = authorizationService.apply(
                request("REQ-9", "C-102", "Z-A1", T1, T2, "3000"));
        assertThat(exact.result()).isEqualTo("GRANTED");
    }

    @Test
    void rejected_overload_is_also_idempotent() {
        AuthorizationRequest req = request("REQ-11", "C-102", "Z-A1", T0, T1, "9999");
        AuthorizationView first = authorizationService.apply(req);
        AuthorizationView replay = authorizationService.apply(req);
        assertThat(first.result()).isEqualTo("REJECTED_OVERLOAD");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.result()).isEqualTo("REJECTED_OVERLOAD");
    }

    @Test
    void release_completed_then_status_released_but_window_kept() {
        authorizationService.apply(request("REQ-12", "C-101", "Z-A1", T0, T1, "1000"));
        AuthorizationView released = authorizationService.release("REQ-12",
                new ReleaseRequest("COMPLETED"));

        assertThat(released.status()).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject(
                "SELECT release_type FROM zone_authorization WHERE request_id = 'REQ-12'",
                String.class)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject(
                "SELECT released_at FROM zone_authorization WHERE request_id = 'REQ-12'",
                java.sql.Timestamp.class)).isNotNull();

        // 释放后该窗口不再阻塞端点相接/之后的新申请
        AuthorizationView next = authorizationService.apply(
                request("REQ-13", "C-102", "Z-A1", T1, T2, "1000"));
        assertThat(next.result()).isEqualTo("GRANTED");
    }

    @Test
    void release_twice_is_idempotent_and_rejected_request_cannot_release() {
        authorizationService.apply(request("REQ-14", "C-101", "Z-A1", T0, T1, "1000"));
        authorizationService.release("REQ-14", new ReleaseRequest("CANCELLED"));
        AuthorizationView again = authorizationService.release("REQ-14",
                new ReleaseRequest("CANCELLED"));
        assertThat(again.status()).isEqualTo("RELEASED");

        authorizationService.apply(request("REQ-15", "C-102", "Z-A1", T0, T1, "9999"));
        assertThatThrownBy(() -> authorizationService.release("REQ-15",
                new ReleaseRequest("COMPLETED")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void invalid_interval_and_unknown_resources() {
        assertThatThrownBy(() -> authorizationService.apply(
                request("REQ-16", "C-101", "Z-A1", T1, T0, "1000")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> authorizationService.apply(
                request("REQ-17", "C-999", "Z-A1", T0, T1, "1000")))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> authorizationService.apply(
                request("REQ-18", "C-101", "Z-NOPE", T0, T1, "1000")))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> authorizationService.get("MISSING"))
                .isInstanceOf(NotFoundException.class);
    }
}
