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
 * 夜间接力押运：接力点设置、双人打卡换班、禁行起点冻结与处置、禁行时段通行限制。
 *
 * <p>合成夜间禁行时段：2026-10-06 00:00（含）~05:00（不含）。
 * R-G3 计划（发运 2026-10-05 22:00）：p0 22:30、p1 23:00、p2 23:30、p3 00:00（恰满禁行起点 → 跨越）。
 */
class RelayEscortTest extends HazmatIntegrationTestBase {

    private static final LocalDateTime DEPARTURE = LocalDateTime.parse("2026-10-05T22:00:00");
    private static final LocalDateTime CURFEW_START = LocalDateTime.parse("2026-10-06T00:00:00");
    private static final LocalDateTime CURFEW_END = LocalDateTime.parse("2026-10-06T05:00:00");

    private String applied(String reqPrefix, String plate) {
        return shipmentNo(apply(reqPrefix + "-apply", plate, "FLAMMABLE", "R-G3", DEPARTURE));
    }

    /** 申请 → 核准 → 设接力点 p2 → 发运。 */
    private String dispatchedWithRelay(String reqPrefix, String plate) {
        String no = applied(reqPrefix, plate);
        approve(no);
        setRelay(no, reqPrefix + "-relay", 2);
        dispatch(no, reqPrefix + "-dispatch");
        return no;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> relay(String shipmentNo) {
        return (Map<String, Object>) view(shipmentNo).get("relay");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> disposals(String shipmentNo) {
        return (List<Map<String, Object>>) view(shipmentNo).get("disposals");
    }

    // ---------- 接力点设置 ----------

    @Test
    void dispatchRequiresRelayWhenCrossingCurfew() {
        String no = applied("rq", "沪A-1001");
        approve(no);
        // 计划 p3 恰满禁行起点 → 跨越禁行时段，未设接力点禁止发运。
        assertThatThrownBy(() -> dispatch(no, "rq-dispatch"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("RELAY_REQUIRED"));

        ServiceResult set = setRelay(no, "rq-relay", 2);
        assertThat(set.httpStatus()).isEqualTo(200);
        assertThat(relay(no).get("status")).isEqualTo("OPEN");
        assertThat(relay(no).get("waypointSeq")).isEqualTo(2);
        assertThat(relay(no).get("curfewStart")).isEqualTo(CURFEW_START.toString());

        assertThat(dispatch(no, "rq-dispatch-2").httpStatus()).isEqualTo(200);
    }

    @Test
    void relayPointMustBeStrictlyBeforeCurfewStart() {
        String no = applied("late", "沪A-1001");
        approve(no);
        // p3 计划 00:00 恰满禁行起点 → 不满足"严格早于"。
        assertThatThrownBy(() -> setRelay(no, "late-relay", 3))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("RELAY_POINT_TOO_LATE"));
        // 不存在的途经点序号。
        assertThatThrownBy(() -> setRelay(no, "late-relay-9", 9))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("WAYPOINT_NOT_FOUND"));
    }

    @Test
    void relayNotRequiredWhenPlanDoesNotCrossCurfew() {
        // 发运 20:00，末点 22:00，不跨越禁行起点。
        String no = shipmentNo(apply("nc-apply", "沪A-1001", "FLAMMABLE", "R-G3",
                LocalDateTime.parse("2026-10-05T20:00:00")));
        approve(no);
        assertThatThrownBy(() -> setRelay(no, "nc-relay", 2))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("NO_CURFEW_CROSSING"));
        // 不跨越时无需接力点即可发运。
        assertThat(dispatch(no, "nc-dispatch").httpStatus()).isEqualTo(200);
    }

    @Test
    void onlyOneOpenRelaySegmentPerShipment() {
        String no = applied("one", "沪A-1001");
        approve(no);
        setRelay(no, "one-relay", 2);
        assertThatThrownBy(() -> setRelay(no, "one-relay-again", 1))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("RELAY_ACTIVE"));
    }

    // ---------- 双人打卡换班 ----------

    @Test
    void dualCheckinCompletesHandoverWithBothEscortsAndTimes() {
        String no = dispatchedWithRelay("dual", "沪A-1001");
        // 单人打卡不生效：运单保持在途，接力段仍 OPEN。
        ServiceResult first = relayCheckin(no, "dual-out", "ESC-OUT-01", "OUTGOING",
                LocalDateTime.parse("2026-10-05T23:20:00"));
        assertThat(first.httpStatus()).isEqualTo(200);
        assertThat(relay(no).get("status")).isEqualTo("OPEN");
        assertThat(view(no).get("status")).isEqualTo("DISPATCHED");

        ServiceResult second = relayCheckin(no, "dual-in", "ESC-IN-02", "INCOMING",
                LocalDateTime.parse("2026-10-05T23:25:00"));
        assertThat(second.httpStatus()).isEqualTo(200);
        // 换班记录含双方编号与时刻。
        Map<String, Object> relay = relay(no);
        assertThat(relay.get("status")).isEqualTo("COMPLETED");
        assertThat(relay.get("outgoingEscort")).isEqualTo("ESC-OUT-01");
        assertThat(relay.get("incomingEscort")).isEqualTo("ESC-IN-02");
        assertThat(relay.get("outgoingTime")).isEqualTo("2026-10-05T23:20");
        assertThat(relay.get("incomingTime")).isEqualTo("2026-10-05T23:25");
        assertThat(events(view(no)).stream()
                .anyMatch(e -> "RELAY_COMPLETED".equals(e.get("eventType")))).isTrue();
        // 运单未冻结，合法时段可继续打卡。
        checkin(no, "dual-p2", 2, LocalDateTime.parse("2026-10-05T23:40:00"));
        assertThat(waypoint(no, 2).get("state")).isEqualTo("CHECKED");
    }

    @Test
    void handoverDoesNotExemptCurfewTravelBan() {
        String no = dispatchedWithRelay("ban", "沪A-1001");
        relayCheckin(no, "ban-out", "ESC-OUT-01", "OUTGOING", LocalDateTime.parse("2026-10-05T23:20:00"));
        relayCheckin(no, "ban-in", "ESC-IN-02", "INCOMING", LocalDateTime.parse("2026-10-05T23:25:00"));
        assertThat(relay(no).get("status")).isEqualTo("COMPLETED");
        // 换班完成也不豁免禁行：禁行时段内打卡拒绝。
        assertThatThrownBy(() -> checkin(no, "ban-p3", 3, LocalDateTime.parse("2026-10-06T01:00:00")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("CURFEW_PERIOD_BLOCKED"));
        // 禁行结束后恢复通行（恰满结束时刻视为可通行）。
        checkin(no, "ban-p3-ok", 3, CURFEW_END);
        assertThat(waypoint(no, 3).get("state")).isEqualTo("CHECKED_LATE");
    }

    @Test
    void outOfOrderRelayCheckinReorderedByTime() {
        String no = dispatchedWithRelay("ooo", "沪A-1001");
        // 接班打卡先回传（时刻更晚），交班后回传（时刻更早）→ 允许，按时刻归位。
        relayCheckin(no, "ooo-in", "ESC-IN-02", "INCOMING", LocalDateTime.parse("2026-10-05T23:25:00"));
        assertThat(relay(no).get("status")).isEqualTo("OPEN");
        relayCheckin(no, "ooo-out", "ESC-OUT-01", "OUTGOING", LocalDateTime.parse("2026-10-05T23:20:00"));
        Map<String, Object> relay = relay(no);
        assertThat(relay.get("status")).isEqualTo("COMPLETED");
        assertThat(relay.get("outgoingTime")).isEqualTo("2026-10-05T23:20");
        assertThat(relay.get("incomingTime")).isEqualTo("2026-10-05T23:25");
    }

    @Test
    void reverseRelayCheckinRejectedAndAlerted() {
        String no = dispatchedWithRelay("rev", "沪A-1001");
        relayCheckin(no, "rev-out", "ESC-OUT-01", "OUTGOING", LocalDateTime.parse("2026-10-05T23:30:00"));
        // 接班时刻早于交班时刻 → 逆序拒绝并告警。
        assertThatThrownBy(() -> relayCheckin(no, "rev-in-bad", "ESC-IN-02", "INCOMING",
                LocalDateTime.parse("2026-10-05T23:20:00")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("CHECKIN_REVERSED"));
        assertThat(events(view(no)).stream()
                .anyMatch(e -> "CHECKIN_REVERSED".equals(e.get("eventType")))).isTrue();
        assertThat(relay(no).get("status")).isEqualTo("OPEN");
        // 合法时刻重新打卡仍完成换班（拒绝不占用角色）。
        relayCheckin(no, "rev-in-ok", "ESC-IN-02", "INCOMING", LocalDateTime.parse("2026-10-05T23:35:00"));
        assertThat(relay(no).get("status")).isEqualTo("COMPLETED");
    }

    @Test
    void duplicateRelayCheckinSameRoleRejected() {
        String no = dispatchedWithRelay("dup", "沪A-1001");
        relayCheckin(no, "dup-out", "ESC-OUT-01", "OUTGOING", LocalDateTime.parse("2026-10-05T23:20:00"));
        // 运单+途经点序号+角色去重：同角色再次打卡拒绝。
        assertThatThrownBy(() -> relayCheckin(no, "dup-out-2", "ESC-OUT-09", "OUTGOING",
                LocalDateTime.parse("2026-10-05T23:22:00")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("RELAY_ALREADY_CHECKED"));
    }

    // ---------- 禁行起点冻结与处置 ----------

    @Test
    void handoverExactlyAtCurfewStartBreachesAndFreezes() {
        String no = dispatchedWithRelay("exact", "沪A-1001");
        relayCheckin(no, "exact-out", "ESC-OUT-01", "OUTGOING", LocalDateTime.parse("2026-10-05T23:55:00"));
        // 接班打卡恰满禁行起点 → 未按时完成换班：冻结新打卡并转处置（同一事务）。
        ServiceResult result = relayCheckin(no, "exact-in", "ESC-IN-02", "INCOMING", CURFEW_START);
        assertThat(result.httpStatus()).isEqualTo(200);
        Map<String, Object> view = view(no);
        assertThat(view.get("status")).isEqualTo("CURFEW_FROZEN");
        assertThat(view.get("terminal")).isEqualTo(true);
        assertThat(relay(no).get("status")).isEqualTo("BREACHED");
        // 冻结与处置登记同事务落库。
        assertThat(disposals(no)).hasSize(1);
        assertThat(disposals(no).get(0).get("reason").toString()).contains("未按时完成换班");
        assertThat(events(view).stream()
                .anyMatch(e -> "CURFEW_FROZEN".equals(e.get("eventType")))).isTrue();
        // 车辆占用释放（非失联冻结，vehicle.frozen 不置位）。
        assertThat(repository.findActiveShipment("沪A-1001")).isEmpty();
        assertThat(repository.findVehicle("沪A-1001").orElseThrow().frozen()).isFalse();
        // 冻结后新打卡（途经点与接力）均被拒。
        assertThatThrownBy(() -> checkin(no, "exact-p3", 3, CURFEW_END))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("SHIPMENT_TERMINAL"));
        assertThatThrownBy(() -> relayCheckin(no, "exact-in-2", "ESC-IN-03", "INCOMING", CURFEW_END))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("SHIPMENT_TERMINAL"));
    }

    @Test
    void scanFreezesOpenRelayAtCurfewStartAndStopsMetering() {
        String no = dispatchedWithRelay("scan", "沪A-1001");
        relayCheckin(no, "scan-out", "ESC-OUT-01", "OUTGOING", LocalDateTime.parse("2026-10-05T23:30:00"));
        // 禁行起点前 1 秒扫描：不冻结；p0（阈值 23:30）正常计程标记超时（扫描不改运单状态）。
        scan(LocalDateTime.parse("2026-10-05T23:59:59"));
        assertThat(view(no).get("status")).isEqualTo("DISPATCHED");
        assertThat(waypoint(no, 0).get("state")).isEqualTo("TIMED_OUT");
        // 恰满禁行起点扫描：换班未完成 → 冻结 + 处置登记；p1（阈值恰为 00:00）不再计程。
        scan(CURFEW_START);
        Map<String, Object> view = view(no);
        assertThat(view.get("status")).isEqualTo("CURFEW_FROZEN");
        assertThat(relay(no).get("status")).isEqualTo("BREACHED");
        assertThat(disposals(no)).hasSize(1);
        // 不得既冻结又继续计程：冻结当次扫描起不再标记超时点。
        assertThat(waypoint(no, 1).get("state")).isEqualTo("PENDING");
        assertThat(waypoint(no, 2).get("state")).isEqualTo("PENDING");
        // 复算：再次扫描幂等，不产生额外处置或事件。
        scan(LocalDateTime.parse("2026-10-06T02:00:00"));
        assertThat(disposals(no)).hasSize(1);
        assertThat(events(view(no)).stream()
                .filter(e -> "CURFEW_FROZEN".equals(e.get("eventType"))).count()).isEqualTo(1);
        assertThat(waypoint(no, 1).get("state")).isEqualTo("PENDING");
    }

    @Test
    void deviationAndCurfewFreezeSimultaneousHitsFormUniqueTerminal() {
        String no = dispatchedWithRelay("both", "沪A-1001");
        // 扫描时刻 00:00：p0（阈值 23:30）与 p1（阈值 00:00）均已超时 → 偏离预警/失联条件命中；
        // 同时接力段到达禁行起点 → 禁行冻结命中。两者同时命中须形成唯一终态。
        scan(CURFEW_START);
        Map<String, Object> view = view(no);
        assertThat(view.get("status")).isEqualTo("CURFEW_FROZEN");
        assertThat(view.get("terminal")).isEqualTo(true);
        // 唯一终态：未升级失联，车辆未因失联冻结，无偏离预警计程。
        assertThat(events(view).stream()
                .anyMatch(e -> "LOST_CONTACT".equals(e.get("eventType")))).isFalse();
        assertThat(events(view).stream()
                .anyMatch(e -> "DEVIATION_WARNING".equals(e.get("eventType")))).isFalse();
        assertThat(repository.findVehicle("沪A-1001").orElseThrow().frozen()).isFalse();
        assertThat(waypoint(no, 0).get("state")).isEqualTo("PENDING");
        assertThat(disposals(no)).hasSize(1);
    }

    // ---------- 幂等 ----------

    @Test
    void relaySetIdempotentSameKeyReplaysAndDifferentParamsConflict() {
        String no = applied("idem", "沪A-1001");
        approve(no);
        ServiceResult first = setRelay(no, "idem-relay", 2);
        ServiceResult replay = setRelay(no, "idem-relay", 2);
        assertThat(replay.httpStatus()).isEqualTo(200);
        assertThat(relay(no).get("relayId")).isEqualTo(
                ((Map<String, Object>) first.body().get("relay")).get("relayId"));
        // 同键异参 → 409。
        assertThatThrownBy(() -> setRelay(no, "idem-relay", 1))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("IDEMPOTENT_FINGERPRINT_MISMATCH"));
    }

    @Test
    void relayCheckinIdempotentSameKeyReplaysAndDifferentParamsConflict() {
        String no = dispatchedWithRelay("idemc", "沪A-1001");
        ServiceResult first = relayCheckin(no, "idemc-out", "ESC-OUT-01", "OUTGOING",
                LocalDateTime.parse("2026-10-05T23:20:00"));
        assertThat(first.httpStatus()).isEqualTo(200);
        // 同键同参重放：返回首次结果，不报 RELAY_ALREADY_CHECKED。
        ServiceResult replay = relayCheckin(no, "idemc-out", "ESC-OUT-01", "OUTGOING",
                LocalDateTime.parse("2026-10-05T23:20:00"));
        assertThat(replay.httpStatus()).isEqualTo(200);
        assertThat(relay(no).get("status")).isEqualTo("OPEN");
        // 同键异参 → 409。
        assertThatThrownBy(() -> relayCheckin(no, "idemc-out", "ESC-OUT-99", "OUTGOING",
                LocalDateTime.parse("2026-10-05T23:20:00")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("IDEMPOTENT_FINGERPRINT_MISMATCH"));
    }
}
