package com.example.starter.hazmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.starter.hazmat.api.ApiException;
import com.example.starter.hazmat.service.ServiceResult;

/**
 * 紧急改线：通过前原路线打卡继续有效；核准事务内旧路线终止 + 新路线生效原子切换。
 */
class RerouteTest extends HazmatIntegrationTestBase {

    private static final LocalDateTime DEPARTURE = LocalDateTime.parse("2026-10-02T08:00:00");

    @Test
    void oldRouteCheckinsRemainValidUntilSwitchAndSwitchIsAtomic() {
        String no = shipmentNo(apply("rr-apply", "沪A-1001", "FLAMMABLE", "R-G3", DEPARTURE));
        approve(no);
        dispatch(no, "rr-dispatch");
        // 原路线 p0 已打卡。
        checkin(no, "rr-ck0", 0, DEPARTURE.plusMinutes(31));

        // 改线待核准期间：原路线仍是当前生效版本，原路线 p1 打卡继续有效。
        ServiceResult applied = reroute(no, "rr-req", "R-G2");
        long pendingApprovalId = ((Number) applied.body().get("pendingApprovalId")).longValue();
        checkin(no, "rr-ck1", 1, DEPARTURE.plusMinutes(61));

        Map<String, Object> before = view(no);
        List<Map<String, Object>> approvalsBefore = approvals(before);
        Map<String, Object> v1Before = approvalsBefore.stream()
                .filter(a -> Integer.valueOf(1).equals(a.get("versionNo"))).findFirst().orElseThrow();
        Map<String, Object> v2Before = approvalsBefore.stream()
                .filter(a -> Integer.valueOf(2).equals(a.get("versionNo"))).findFirst().orElseThrow();
        assertThat(v1Before.get("status")).isEqualTo("APPROVED");
        assertThat(v1Before.get("current")).isEqualTo(true);
        assertThat(v2Before.get("status")).isEqualTo("PENDING");
        assertThat(v2Before.get("current")).isEqualTo(false);

        // 核准切换：同一事务完成旧终止、新生效、运单指针切换。
        ServiceResult switched = service.approveReroute(no, pendingApprovalId);
        assertThat(status(switched)).isEqualTo("IN_TRANSIT");

        List<Map<String, Object>> approvalsAfter = approvals(view(no));
        Map<String, Object> v1 = approvalsAfter.stream()
                .filter(a -> Integer.valueOf(1).equals(a.get("versionNo"))).findFirst().orElseThrow();
        Map<String, Object> v2 = approvalsAfter.stream()
                .filter(a -> Integer.valueOf(2).equals(a.get("versionNo"))).findFirst().orElseThrow();
        assertThat(v1.get("status")).isEqualTo("TERMINATED");
        assertThat(v1.get("current")).isEqualTo(false);
        assertThat(v2.get("status")).isEqualTo("APPROVED");
        assertThat(v2.get("current")).isEqualTo(true);

        // 原路线打卡事实保留（p0/p1 仍为 CHECKED）。
        List<Map<String, Object>> oldWaypoints = waypointsOf(v1);
        assertThat(oldWaypoints.get(0).get("state")).isEqualTo("CHECKED");
        assertThat(oldWaypoints.get(1).get("state")).isEqualTo("CHECKED");
        // 切换事件记录。
        assertThat(events(view(no)).stream()
                .anyMatch(e -> "REROUTE_SWITCH".equals(e.get("eventType")))).isTrue();

        // 新路线独立序号空间：新 p0 可打卡，且不与旧 p0 冲突。
        ServiceResult newCk = checkin(no, "rr-new0", 0, DEPARTURE.plusMinutes(34));
        assertThat(status(newCk)).isEqualTo("IN_TRANSIT");
        Map<String, Object> currentV2 = currentApprovalView(no);
        assertThat(waypointsOf(currentV2).get(0).get("state")).isEqualTo("CHECKED");
    }

    @Test
    void rejectedRerouteKeepsOriginalRouteActive() {
        String no = shipmentNo(apply("rr2-apply", "沪A-1002", "FLAMMABLE", "R-G3", DEPARTURE));
        approve(no);
        dispatch(no, "rr2-dispatch");
        // 改到等级不足的 R-G1（FLAMMABLE 要求 2，R-G1 等级 1）→ 驳回 422，原路线仍有效。
        ServiceResult rejected = reroute(no, "rr2-req", "R-G1");
        long pendingId = ((Number) rejected.body().get("pendingApprovalId")).longValue();
        ServiceResult decision = service.approveReroute(no, pendingId);
        assertThat(decision.httpStatus()).isEqualTo(422);
        Map<String, Object> current = currentApprovalView(no);
        assertThat(current.get("routeCode")).isEqualTo("R-G3");
        assertThat(current.get("status")).isEqualTo("APPROVED");
        // 原路线仍可打卡。
        checkin(no, "rr2-ck0", 0, DEPARTURE.plusMinutes(32));
        assertThat(waypoint(no, 0).get("state")).isEqualTo("CHECKED");
    }

    @Test
    void cannotHaveTwoPendingReroutes() {
        String no = shipmentNo(apply("rr3-apply", "沪B-2001", "FLAMMABLE", "R-G3", DEPARTURE));
        approve(no);
        dispatch(no, "rr3-dispatch");
        reroute(no, "rr3-req-1", "R-G2");
        assertThatThrownBy(() -> reroute(no, "rr3-req-2", "R-G2"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("REROUTE_PENDING"));
    }
}
