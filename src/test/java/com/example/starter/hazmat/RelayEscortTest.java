package com.example.starter.hazmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.starter.hazmat.api.ApiException;
import com.example.starter.hazmat.service.ServiceResult;

/**
 * 夜间接力押运：安全接力点设置、双人打卡换班、禁行起点冻结与处置。
 *
 * <p>R-N3 夜间禁行 [2026-10-03 00:00, 05:00)；傍晚 20:00 发运时计划通过时刻：
 * p0 21:00、p1 22:00、p2 23:00（安全接力站）、p3 00:00（恰满禁行起点）、p4 01:00。
 * 换班截止 = 禁行起点 2026-10-03T00:00。
 */
class RelayEscortTest extends HazmatIntegrationTestBase {

    private static final LocalDateTime DEPARTURE = LocalDateTime.parse("2026-10-02T20:00:00");
    private static final LocalDateTime CURFEW_START = LocalDateTime.parse("2026-10-03T00:00:00");

    /** 申请并核准一条 R-N3 夜间运单（未发运）。 */
    private String approvedNightShipment(String reqPrefix, String plate) {
        String no = shipmentNo(apply(reqPrefix + "-apply", plate, "EXPLOSIVE", "R-N3", DEPARTURE));
        approve(no);
        return no;
    }

    /** 申请、核准、设置 p2 接力点并发运，返回 [shipmentNo, relayId]。 */
    private Object[] dispatchedWithRelay(String reqPrefix, String plate) {
        String no = approvedNightShipment(reqPrefix, plate);
        long relayId = relayId(createRelay(no, reqPrefix + "-relay", 2, "ESC-A", "ESC-B"));
        dispatch(no, reqPrefix + "-dispatch");
        return new Object[]{no, relayId};
    }

    private long relayId(ServiceResult result) {
        return ((Number) result.body().get("relayId")).longValue();
    }

    @Test
    void dispatchRequiresRelayWhenCurfewAhead() {
        String no = approvedNightShipment("guard", "沪C-3001");
        // 计划到达禁行起点但未设接力点 → 拒绝发运。
        assertThatThrownBy(() -> dispatch(no, "guard-dispatch"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("CURFEW_RELAY_REQUIRED"));

        createRelay(no, "guard-relay", 2, "ESC-A", "ESC-B");
        ServiceResult dispatched = dispatch(no, "guard-dispatch-2");
        assertThat(status(dispatched)).isEqualTo("DISPATCHED");
    }

    @Test
    void relayPointMustBeBeforeCurfewStart() {
        String no = approvedNightShipment("incurfew", "沪C-3001");
        // p3 计划 00:00 恰满禁行起点 → 不是合法接力点。
        assertThatThrownBy(() -> createRelay(no, "incurfew-relay", 3, "ESC-A", "ESC-B"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("RELAY_POINT_IN_CURFEW"));
    }

    @Test
    void relayNotApplicableOnRouteWithoutCurfew() {
        String no = shipmentNo(apply("nocurfew-apply", "沪C-3001", "FLAMMABLE", "R-G3",
                LocalDateTime.parse("2026-10-02T08:00:00")));
        approve(no);
        assertThatThrownBy(() -> createRelay(no, "nocurfew-relay", 1, "ESC-A", "ESC-B"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("CURFEW_NOT_APPLICABLE"));
    }

    @Test
    void sameEscortOnBothSidesRejected() {
        String no = approvedNightShipment("sameesc", "沪C-3001");
        assertThatThrownBy(() -> createRelay(no, "sameesc-relay", 2, "ESC-A", "ESC-A"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("ESCORT_SAME"));
    }

    @Test
    void singleActiveRelayPerShipment() {
        String no = approvedNightShipment("onerelay", "沪C-3001");
        createRelay(no, "onerelay-1", 2, "ESC-A", "ESC-B");
        assertThatThrownBy(() -> createRelay(no, "onerelay-2", 1, "ESC-C", "ESC-D"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("RELAY_IN_PROGRESS"));
    }

    @Test
    void singleEscortCheckinDoesNotCompleteShift() {
        Object[] ctx = dispatchedWithRelay("single", "沪C-3001");
        String no = (String) ctx[0];
        long relayId = (long) ctx[1];

        relayCheckin(no, relayId, "single-out", "ESC-A", DEPARTURE.plusMinutes(210)); // 23:30
        Map<String, Object> relay = relay(no, relayId);
        // 单人打卡不生效：接力段仍进行中，换班未生效。
        assertThat(relay.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(relay.get("completedAt")).isNull();
        assertThat(relay.get("outgoingTime")).isEqualTo("2026-10-02T23:30");

        relayCheckin(no, relayId, "single-in", "ESC-B", DEPARTURE.plusMinutes(225)); // 23:45
        relay = relay(no, relayId);
        assertThat(relay.get("status")).isEqualTo("COMPLETED");
        // 换班记录含双方编号与时刻，生效时刻=接班打卡时刻。
        assertThat(relay.get("outgoingEscort")).isEqualTo("ESC-A");
        assertThat(relay.get("incomingEscort")).isEqualTo("ESC-B");
        assertThat(relay.get("outgoingTime")).isEqualTo("2026-10-02T23:30");
        assertThat(relay.get("incomingTime")).isEqualTo("2026-10-02T23:45");
        assertThat(relay.get("completedAt")).isEqualTo("2026-10-02T23:45");
        assertThat(events(view(no)).stream()
                .anyMatch(e -> "RELAY_SHIFT_COMPLETED".equals(e.get("eventType")))).isTrue();
    }

    @Test
    void outOfOrderCheckinReorderedByTime() {
        Object[] ctx = dispatchedWithRelay("ooo", "沪C-3001");
        String no = (String) ctx[0];
        long relayId = (long) ctx[1];

        // 接班打卡先回传（时刻更晚），交班后回传（时刻更早）→ 乱序允许，按时刻归位。
        relayCheckin(no, relayId, "ooo-in", "ESC-B", DEPARTURE.plusMinutes(225));
        assertThat(relay(no, relayId).get("status")).isEqualTo("IN_PROGRESS");
        relayCheckin(no, relayId, "ooo-out", "ESC-A", DEPARTURE.plusMinutes(210));
        Map<String, Object> relay = relay(no, relayId);
        assertThat(relay.get("status")).isEqualTo("COMPLETED");
        assertThat(relay.get("completedAt")).isEqualTo("2026-10-02T23:45");
    }

    @Test
    void reverseCheckinRejectedAndAlerted() {
        Object[] ctx = dispatchedWithRelay("rev", "沪C-3001");
        String no = (String) ctx[0];
        long relayId = (long) ctx[1];

        relayCheckin(no, relayId, "rev-out", "ESC-A", DEPARTURE.plusMinutes(230)); // 23:50
        // 接班时刻 23:40 早于交班 23:50 → 逆序拒绝并告警。
        assertThatThrownBy(() -> relayCheckin(no, relayId, "rev-in-bad", "ESC-B", DEPARTURE.plusMinutes(220)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("RELAY_CHECKIN_REVERSED"));
        assertThat(relay(no, relayId).get("status")).isEqualTo("IN_PROGRESS");
        assertThat(events(view(no)).stream()
                .anyMatch(e -> "RELAY_CHECKIN_REVERSED".equals(e.get("eventType")))).isTrue();

        // 合法时刻重新打卡仍生效。
        relayCheckin(no, relayId, "rev-in-ok", "ESC-B", DEPARTURE.plusMinutes(235));
        assertThat(relay(no, relayId).get("status")).isEqualTo("COMPLETED");
    }

    @Test
    void duplicateEscortCheckinRejected() {
        Object[] ctx = dispatchedWithRelay("dup", "沪C-3001");
        String no = (String) ctx[0];
        long relayId = (long) ctx[1];

        relayCheckin(no, relayId, "dup-1", "ESC-A", DEPARTURE.plusMinutes(210));
        assertThatThrownBy(() -> relayCheckin(no, relayId, "dup-2", "ESC-A", DEPARTURE.plusMinutes(212)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("RELAY_ALREADY_CHECKED"));
    }

    @Test
    void escortNotInSegmentRejected() {
        Object[] ctx = dispatchedWithRelay("stranger", "沪C-3001");
        String no = (String) ctx[0];
        long relayId = (long) ctx[1];

        assertThatThrownBy(() -> relayCheckin(no, relayId, "stranger-1", "ESC-X", DEPARTURE.plusMinutes(210)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("ESCORT_NOT_IN_SEGMENT"));
    }

    @Test
    void relayIdempotencyReplayAndConflict() {
        String no = approvedNightShipment("idem", "沪C-3001");
        ServiceResult first = createRelay(no, "idem-relay", 2, "ESC-A", "ESC-B");
        // 同键同参：返回首次结果（同一 relayId）。
        ServiceResult replay = createRelay(no, "idem-relay", 2, "ESC-A", "ESC-B");
        assertThat(relayId(replay)).isEqualTo(relayId(first));
        assertThat(relays(view(no))).hasSize(1);
        // 同键异参：409。
        assertThatThrownBy(() -> createRelay(no, "idem-relay", 1, "ESC-A", "ESC-B"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("IDEMPOTENT_FINGERPRINT_MISMATCH"));
    }

    @Test
    void scanFreezesAtExactCurfewStartWithDisposal() {
        Object[] ctx = dispatchedWithRelay("freeze", "沪C-3001");
        String no = (String) ctx[0];
        long relayId = (long) ctx[1];

        // 恰满禁行起点扫描 → 冻结并同事务登记处置。
        ServiceResult scan = scan(CURFEW_START);
        assertThat(scan.body().get("affected").toString()).contains(no);

        Map<String, Object> v = view(no);
        assertThat(v.get("status")).isEqualTo("CURFEW_FROZEN");
        assertThat(v.get("terminal")).isEqualTo(true);
        assertThat(relay(no, relayId).get("status")).isEqualTo("FROZEN");
        assertThat(disposals(v)).hasSize(1);
        assertThat(disposals(v).get(0).get("reason").toString()).contains("夜间禁行起点");
        assertThat(events(v).stream().anyMatch(e -> "CURFEW_FROZEN".equals(e.get("eventType")))).isTrue();

        // 冻结后新打卡（途经点与接力）均被拒绝。
        assertThatThrownBy(() -> checkin(no, "freeze-wp", 0, DEPARTURE.plusMinutes(61)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("SHIPMENT_TERMINAL"));
        assertThatThrownBy(() -> relayCheckin(no, relayId, "freeze-rc", "ESC-A", DEPARTURE.plusMinutes(210)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("SHIPMENT_TERMINAL"));
    }

    @Test
    void scanBeforeCurfewStartKeepsRelayAlive() {
        Object[] ctx = dispatchedWithRelay("before", "沪C-3001");
        String no = (String) ctx[0];
        long relayId = (long) ctx[1];

        // 21:30 扫描：未到任何途经点超时阈值，也未到禁行起点 → 无冻结无计程变化。
        ServiceResult scan = scan(DEPARTURE.plusMinutes(90));
        assertThat(scan.body().get("affected").toString()).doesNotContain(no);
        assertThat(view(no).get("status")).isEqualTo("DISPATCHED");
        assertThat(relay(no, relayId).get("status")).isEqualTo("IN_PROGRESS");
    }

    @Test
    void completedRelaySurvivesCurfewScan() {
        Object[] ctx = dispatchedWithRelay("done", "沪C-3001");
        String no = (String) ctx[0];
        long relayId = (long) ctx[1];

        // 途经点准点打卡（避免超时计程干扰），接力双方 23:30/23:45 完成换班。
        checkin(no, "done-wp0", 0, DEPARTURE.plusMinutes(60));
        checkin(no, "done-wp1", 1, DEPARTURE.plusMinutes(120));
        checkin(no, "done-wp2", 2, DEPARTURE.plusMinutes(180));
        relayCheckin(no, relayId, "done-out", "ESC-A", DEPARTURE.plusMinutes(210));
        relayCheckin(no, relayId, "done-in", "ESC-B", DEPARTURE.plusMinutes(225));
        scan(CURFEW_START);
        // 换班已完成：禁行扫描不冻结运单，无处置登记。
        assertThat(view(no).get("status")).isEqualTo("IN_TRANSIT");
        assertThat(disposals(view(no))).isEmpty();
    }

    @Test
    void lateRelayCheckinAtCurfewStartFreezesInline() {
        Object[] ctx = dispatchedWithRelay("late", "沪C-3001");
        String no = (String) ctx[0];
        long relayId = (long) ctx[1];

        // 打卡时刻恰满禁行起点 → 未按时换班，409 且冻结+处置同事务落库。
        ServiceResult result = relayCheckin(no, relayId, "late-in", "ESC-B", CURFEW_START);
        assertThat(result.httpStatus()).isEqualTo(409);
        assertThat(result.body().get("errorCode")).isEqualTo("CURFEW_REACHED");

        Map<String, Object> v = view(no);
        assertThat(v.get("status")).isEqualTo("CURFEW_FROZEN");
        assertThat(disposals(v)).hasSize(1);
        assertThat(relay(no, relayId).get("status")).isEqualTo("FROZEN");
    }

    @Test
    void freezeWinsOverDeviationMeteringInSameScan() {
        Object[] ctx = dispatchedWithRelay("unique", "沪C-3001");
        String no = (String) ctx[0];

        // 扫描时刻同时满足：多个途经点超时（偏离预警）与接力段禁行冻结 → 唯一终态为禁行冻结，
        // 冻结后不再计程：途经点保持 PENDING，无偏离预警事件。
        scan(CURFEW_START);
        Map<String, Object> v = view(no);
        assertThat(v.get("status")).isEqualTo("CURFEW_FROZEN");
        assertThat(waypointsOf(currentApprovalView(no)))
                .allSatisfy(w -> assertThat(w.get("state")).isEqualTo("PENDING"));
        assertThat(events(v).stream()
                .noneMatch(e -> "DEVIATION_WARNING".equals(e.get("eventType"))
                        || "LOST_CONTACT".equals(e.get("eventType")))).isTrue();
    }
}
