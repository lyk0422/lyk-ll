package com.example.starter.hazmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.example.starter.hazmat.api.ApiException;

/**
 * 超时监控与失联升级：恰 60 分钟边界、偏离预警、连续 2 点超时升级失联并冻结车辆。
 *
 * <p>R-G1 计划（发运 08:00）：p0 08:30 阈值 09:30、p1 09:00 阈值 10:00、p2 09:30 阈值 10:30。
 */
class MonitorAndLostContactTest extends HazmatIntegrationTestBase {

    private static final LocalDateTime DEPARTURE = LocalDateTime.parse("2026-10-02T08:00:00");

    private String dispatched(String plate) {
        String no = shipmentNo(apply("m-apply-" + plate, plate, "TOXIC", "R-G1", DEPARTURE));
        approve(no);
        dispatch(no, "m-dispatch-" + plate);
        return no;
    }

    @Test
    void deadlineBoundarySinglePointIsWarningOnly() {
        String no = dispatched("沪A-1001");
        // 09:29:59：p0 未到阈值（恰满 60 分钟才算），无标记。
        scan(LocalDateTime.parse("2026-10-02T09:29:59"));
        assertThat(waypoint(no, 0).get("state")).isEqualTo("PENDING");
        assertThat(view(no).get("status")).isEqualTo("DISPATCHED");

        // 09:30:00 恰满 60 分钟：p0 标记偏离预警，仅 1 点不离联。
        scan(LocalDateTime.parse("2026-10-02T09:30:00"));
        assertThat(waypoint(no, 0).get("state")).isEqualTo("TIMED_OUT");
        assertThat(view(no).get("status")).isEqualTo("DISPATCHED");
        assertThat(events(view(no)).stream()
                .anyMatch(e -> "DEVIATION_WARNING".equals(e.get("eventType")))).isTrue();

        // 复算：再次扫描幂等，不重复产生超时点。
        var again = scan(LocalDateTime.parse("2026-10-02T09:45:00"));
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> affected =
                (java.util.List<java.util.Map<String, Object>>) again.body().get("affected");
        assertThat(affected).isEmpty();
        assertThat(events(view(no)).stream()
                .filter(e -> "DEVIATION_WARNING".equals(e.get("eventType"))).count()).isEqualTo(1);
    }

    @Test
    void twoConsecutiveTimeoutsEscalateToLostContactAndFreeze() {
        String no = dispatched("沪A-1002");
        // 10:00：p0、p1 均超时（恰 2 个连续点）→ 失联终态 + 车辆冻结 + 占用释放。
        scan(LocalDateTime.parse("2026-10-02T10:00:00"));
        var view = view(no);
        assertThat(view.get("status")).isEqualTo("LOST_CONTACT");
        assertThat(view.get("terminal")).isEqualTo(true);
        assertThat(view.get("vehicleFrozen")).isEqualTo(true);
        assertThat(repository.findVehicle("沪A-1002").orElseThrow().frozen()).isTrue();
        assertThat(repository.findActiveShipment("沪A-1002")).isEmpty();
        assertThat(events(view).stream().anyMatch(e -> "LOST_CONTACT".equals(e.get("eventType")))).isTrue();

        // 冻结后新车申请被拒。
        assertThatThrownBy(() -> apply("m-after-freeze", "沪A-1002", "TOXIC", "R-G1", DEPARTURE))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("VEHICLE_FROZEN"));
        // 失联运单不可再打卡。
        assertThatThrownBy(() -> checkin(no, "m-late", 2, DEPARTURE.plusMinutes(100)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("SHIPMENT_TERMINAL"));
    }

    @Test
    void nonConsecutiveTimeoutsDoNotEscalate() {
        String no = dispatched("沪B-2001");
        // 先打中间点 p1（时刻 09:05，无相邻已打卡点，合法）。
        checkin(no, "m-p1", 1, DEPARTURE.plusMinutes(65));
        // 10:30：p0 与 p2 超时，但被已打卡的 p1 隔开，不连续 → 不离联。
        scan(LocalDateTime.parse("2026-10-02T10:30:00"));
        var view = view(no);
        assertThat(view.get("status")).isEqualTo("IN_TRANSIT");
        assertThat(waypoint(no, 0).get("state")).isEqualTo("TIMED_OUT");
        assertThat(waypoint(no, 2).get("state")).isEqualTo("TIMED_OUT");
        assertThat(events(view).stream().anyMatch(e -> "LOST_CONTACT".equals(e.get("eventType")))).isFalse();
    }

    @Test
    void lateCheckinAfterTimeoutClearsConsecutiveChain() {
        String no = dispatched("沪A-1001");
        // 09:30 仅 p0 超时。
        scan(LocalDateTime.parse("2026-10-02T09:30:00"));
        // p0 迟到补打成功（CHECKED_LATE）。
        checkin(no, "m-p0-late", 0, LocalDateTime.parse("2026-10-02T09:35:00"));
        assertThat(waypoint(no, 0).get("state")).isEqualTo("CHECKED_LATE");
        // 10:00 仅 p1 超时：与已补打的 p0 不构成连续两个 TIMED_OUT → 不离联。
        scan(LocalDateTime.parse("2026-10-02T10:00:00"));
        assertThat(view(no).get("status")).isEqualTo("IN_TRANSIT");
    }
}
