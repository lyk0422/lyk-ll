package com.example.starter.hazmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.starter.hazmat.api.ApiException;
import com.example.starter.hazmat.service.ServiceResult;

/**
 * 夜间禁行冻结：恰满禁行起点判定、冻结与处置登记同事务、冻结后拒绝新打卡、
 * 偏离预警与禁行冻结同时命中时唯一终态、禁行时段内禁止通行打卡。
 *
 * <p>场景：R-G3 夜间禁行 2026-10-03 00:00~05:00；运单 2026-10-02 22:30 发运，
 * 计划通过 p0 23:00（阈值 00:00）、p1 23:30、p2 00:00、p3 00:30。
 */
class CurfewFreezeTest extends HazmatIntegrationTestBase {

    private static final LocalDateTime DEPARTURE = LocalDateTime.parse("2026-10-02T22:30:00");
    private static final LocalDateTime CURFEW_START = LocalDateTime.parse("2026-10-03T00:00:00");
    private static final LocalDateTime CURFEW_END = LocalDateTime.parse("2026-10-03T05:00:00");

    private String dispatched(String reqPrefix, String plate) {
        String no = shipmentNo(apply(reqPrefix + "-apply", plate, "FLAMMABLE", "R-G3", DEPARTURE));
        approve(no);
        dispatch(no, reqPrefix + "-dispatch");
        return no;
    }

    private Map<String, Object> segmentOf(String shipmentNo, int relaySeq) {
        return relaySegments(view(shipmentNo)).stream()
                .filter(s -> Integer.valueOf(relaySeq).equals(s.get("relaySeq")))
                .findFirst().orElseThrow();
    }

    @Test
    void exactlyAtCurfewStartFreezesWithDisposalInSameTransaction() {
        String no = dispatched("cf-exact", "沪A-1001");
        setRelayPoint(no, "cf-exact-rp", 1);
        // 禁行起点前 1 秒：未冻结。
        scan(LocalDateTime.parse("2026-10-02T23:59:59"));
        assertThat(view(no).get("status")).isEqualTo("DISPATCHED");

        // 恰满禁行起点：冻结 + 处置登记同一事务落库。
        scan(CURFEW_START);
        Map<String, Object> view = view(no);
        assertThat(view.get("status")).isEqualTo("CURFEW_FROZEN");
        assertThat(view.get("terminal")).isEqualTo(true);
        assertThat(disposals(view)).hasSize(1);
        assertThat(disposals(view).get(0).get("curfewStart")).isEqualTo(CURFEW_START.toString());
        assertThat(segmentOf(no, 1).get("status")).isEqualTo("FROZEN");
        assertThat(events(view).stream()
                .anyMatch(e -> "CURFEW_FROZEN".equals(e.get("eventType")))).isTrue();
        // 终态释放车辆占用；车辆不因禁行冻结而标记失联冻结。
        assertThat(repository.findActiveShipment("沪A-1001")).isEmpty();
        assertThat(repository.findVehicle("沪A-1001").orElseThrow().frozen()).isFalse();

        // 冻结后新打卡被拒绝（普通打卡与接力打卡均拒绝）。
        assertThatThrownBy(() -> checkin(no, "cf-exact-ck", 0, LocalDateTime.parse("2026-10-02T23:05:00")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("SHIPMENT_TERMINAL"));
        assertThatThrownBy(() -> relayCheckin(no, "cf-exact-rc", "ESC-01", "OUT",
                LocalDateTime.parse("2026-10-02T23:20:00")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("SHIPMENT_TERMINAL"));
        // 冻结运单不可核销。
        assertThatThrownBy(() -> complete(no, "cf-exact-done"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("ALREADY_CURFEW_FROZEN"));
    }

    @Test
    void handoverBeforeCurfewStartIsNotFrozen() {
        String no = dispatched("cf-ok", "沪A-1002");
        setRelayPoint(no, "cf-ok-rp", 1);
        clock.setTime(LocalDateTime.parse("2026-10-02T23:00:00"));
        relayCheckin(no, "cf-ok-out", "ESC-01", "OUT", LocalDateTime.parse("2026-10-02T23:20:00"));
        relayCheckin(no, "cf-ok-in", "ESC-02", "IN", LocalDateTime.parse("2026-10-02T23:25:00"));

        // 换班已在禁行起点前完成：扫描不冻结，无处置登记。
        scan(CURFEW_START);
        Map<String, Object> view = view(no);
        assertThat(view.get("status")).isEqualTo("DISPATCHED");
        assertThat(disposals(view)).isEmpty();
        assertThat(segmentOf(no, 1).get("status")).isEqualTo("COMPLETED");
    }

    @Test
    void lazyFreezeOnCheckinAfterCurfewStart() {
        String no = dispatched("cf-lazy", "沪A-1001");
        setRelayPoint(no, "cf-lazy-rp", 1);
        // 未扫描、未换班；业务时钟越过禁行起点后，新打卡触发懒冻结（冻结+处置同事务提交）。
        clock.setTime(LocalDateTime.parse("2026-10-03T00:05:00"));
        ServiceResult result = checkin(no, "cf-lazy-ck", 0, LocalDateTime.parse("2026-10-02T23:05:00"));
        assertThat(result.httpStatus()).isEqualTo(409);
        assertThat(result.body().get("errorCode")).isEqualTo("CURFEW_FROZEN");
        Map<String, Object> view = view(no);
        assertThat(view.get("status")).isEqualTo("CURFEW_FROZEN");
        assertThat(disposals(view)).hasSize(1);
        // 打卡未写入：途经点仍为 PENDING。
        assertThat(waypoint(no, 0).get("state")).isEqualTo("PENDING");
    }

    @Test
    void deviationWarningAndCurfewFreezeYieldSingleTerminal() {
        // 发运 22:30：p0 计划 23:00 阈值 00:00、p1 计划 23:30 阈值 00:30。
        // 扫描 00:30：p0、p1 连续超时（偏离预警+失联条件）与禁行冻结同时命中。
        String no = dispatched("cf-race", "沪A-1002");
        setRelayPoint(no, "cf-race-rp", 1);
        scan(LocalDateTime.parse("2026-10-03T00:30:00"));
        Map<String, Object> view = view(no);
        // 唯一终态：禁行冻结，不得既冻结又计程为失联。
        assertThat(view.get("status")).isEqualTo("CURFEW_FROZEN");
        assertThat(view.get("terminal")).isEqualTo(true);
        long terminalEvents = events(view).stream()
                .filter(e -> "LOST_CONTACT".equals(e.get("eventType"))
                        || "CURFEW_FROZEN".equals(e.get("eventType")))
                .count();
        assertThat(terminalEvents).isEqualTo(1);
        // 偏离预警仍记录，但车辆不按失联冻结。
        assertThat(events(view).stream()
                .anyMatch(e -> "DEVIATION_WARNING".equals(e.get("eventType")))).isTrue();
        assertThat(repository.findVehicle("沪A-1002").orElseThrow().frozen()).isFalse();
        assertThat(disposals(view)).hasSize(1);
    }

    @Test
    void checkinDuringCurfewWindowRejectedEvenAfterHandover() {
        String no = dispatched("cf-window", "沪B-2001");
        setRelayPoint(no, "cf-window-rp", 1);
        clock.setTime(LocalDateTime.parse("2026-10-02T23:00:00"));
        relayCheckin(no, "cf-window-out", "ESC-01", "OUT", LocalDateTime.parse("2026-10-02T23:20:00"));
        relayCheckin(no, "cf-window-in", "ESC-02", "IN", LocalDateTime.parse("2026-10-02T23:25:00"));
        // 换班完成不豁免禁行：禁行窗口内打卡时刻被拒绝。
        clock.setTime(LocalDateTime.parse("2026-10-03T01:00:00"));
        assertThatThrownBy(() -> checkin(no, "cf-window-ck2", 2, LocalDateTime.parse("2026-10-03T01:00:00")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("NIGHT_CURFEW_PERIOD"));
        // 恰满禁行结束时刻恢复合法：可继续通行打卡（迟到补打）。
        clock.setTime(CURFEW_END);
        ServiceResult after = checkin(no, "cf-window-ck2b", 2, CURFEW_END);
        assertThat(after.httpStatus()).isEqualTo(200);
        assertThat(waypoint(no, 2).get("state")).isEqualTo("CHECKED_LATE");
    }
}
