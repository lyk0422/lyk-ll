package com.example.starter.hazmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.starter.hazmat.api.ApiException;
import com.example.starter.hazmat.service.ServiceResult;

/**
 * 受控接力段 IN 确认门禁：须来自预约人员、实际接班时刻落在班段 [start,end) 内
 * （恰到 end 拒绝）、重查资格未撤销；新门禁不阻止原人员 OUT 离岗；
 * 未启用管控的旧接力段保持原行为。
 *
 * <p>班段 [15:00,15:30) UTC = 23:00~23:30 上海；班段 [15:30,16:00) UTC = 23:30~24:00 上海。
 */
class ControlledRelayCheckinTest extends HazmatIntegrationTestBase {

    private static final LocalDateTime DEPARTURE = LocalDateTime.parse("2026-10-02T22:30:00");
    /** 合成资格 ESC-B（data.sql id=2）。 */
    private static final long QUAL_ESC_B = 2L;

    @BeforeEach
    void fixClock() {
        clock.setTime(LocalDateTime.parse("2026-10-02T22:30:00"));
    }

    private String dispatchedControlled(String reqPrefix, String plate) {
        String no = shipmentNo(apply(reqPrefix + "-apply", plate, "FLAMMABLE", "R-G3", DEPARTURE));
        approve(no);
        dispatch(no, reqPrefix + "-dispatch");
        assertThat(setControlledRelayPoint(no, reqPrefix + "-rp", 1).httpStatus()).isEqualTo(200);
        return no;
    }

    private Map<String, Object> segmentOf(String shipmentNo) {
        return relaySegments(view(shipmentNo)).get(0);
    }

    @Test
    void controlledSegmentInRequiresReservationButOutIsFree() {
        String no = dispatchedControlled("cg-req", "沪A-1001");
        // 无预约 IN：拒绝。
        assertThatThrownBy(() -> relayCheckin(no, "cg-req-in", "ESC-B", "IN",
                LocalDateTime.parse("2026-10-02T23:45:00")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("RELAY_RESERVATION_REQUIRED"));
        // 新门禁不阻止原人员 OUT 离岗：无预约也可交班。
        ServiceResult out = relayCheckin(no, "cg-req-out", "ESC-01", "OUT",
                LocalDateTime.parse("2026-10-02T23:40:00"));
        assertThat(out.httpStatus()).isEqualTo(200);
        assertThat(out.body().get("handoverCompleted")).isEqualTo(false);
        // OUT 后 IN 仍须预约。
        assertThatThrownBy(() -> relayCheckin(no, "cg-req-in2", "ESC-B", "IN",
                LocalDateTime.parse("2026-10-02T23:45:00")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("RELAY_RESERVATION_REQUIRED"));
        assertThat(segmentOf(no).get("status")).isEqualTo("IN_PROGRESS");
    }

    @Test
    void inMustComeFromReservedEscort() {
        String no = dispatchedControlled("cg-who", "沪A-1001");
        reserve(no, "cg-who-res", 1, "ESC-B",
                LocalDateTime.parse("2026-10-02T15:30:00"), LocalDateTime.parse("2026-10-02T16:00:00"));
        // 非预约人员 IN：拒绝。
        assertThatThrownBy(() -> relayCheckin(no, "cg-who-in", "ESC-C", "IN",
                LocalDateTime.parse("2026-10-02T23:45:00")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("RELAY_RESERVATION_ESCORT_MISMATCH"));
        // 预约人员 IN：成功。
        relayCheckin(no, "cg-who-out", "ESC-01", "OUT", LocalDateTime.parse("2026-10-02T23:40:00"));
        ServiceResult in = relayCheckin(no, "cg-who-in-ok", "ESC-B", "IN",
                LocalDateTime.parse("2026-10-02T23:45:00"));
        assertThat(in.body().get("handoverCompleted")).isEqualTo(true);
        assertThat(segmentOf(no).get("inEscortId")).isEqualTo("ESC-B");
    }

    @Test
    void checkinTimeMustFallWithinShiftAndEndIsExclusive() {
        String no = dispatchedControlled("cg-time", "沪A-1001");
        // 班段 [15:00,15:30) UTC = 23:00~23:30 上海。
        reserve(no, "cg-time-res", 1, "ESC-B",
                LocalDateTime.parse("2026-10-02T15:00:00"), LocalDateTime.parse("2026-10-02T15:30:00"));
        // 恰到 end（23:30 上海 = 15:30 UTC）：拒绝。
        assertThatThrownBy(() -> relayCheckin(no, "cg-time-end", "ESC-B", "IN",
                LocalDateTime.parse("2026-10-02T23:30:00")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("RELAY_SHIFT_OUT_OF_RANGE"));
        // 早于 start（22:50 上海 = 14:50 UTC）：拒绝。
        assertThatThrownBy(() -> relayCheckin(no, "cg-time-early", "ESC-B", "IN",
                LocalDateTime.parse("2026-10-02T22:50:00")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("RELAY_SHIFT_OUT_OF_RANGE"));
        // 拒绝不产生打卡记录。
        assertThat(segmentOf(no).get("status")).isEqualTo("IN_PROGRESS");
        // 班段内（23:29 上海 = 15:29 UTC）：成功。
        relayCheckin(no, "cg-time-out", "ESC-01", "OUT", LocalDateTime.parse("2026-10-02T23:20:00"));
        ServiceResult in = relayCheckin(no, "cg-time-ok", "ESC-B", "IN",
                LocalDateTime.parse("2026-10-02T23:29:00"));
        assertThat(in.body().get("handoverCompleted")).isEqualTo(true);
    }

    @Test
    void revokedQualificationBlocksInAfterCheckinStarted() {
        String no = dispatchedControlled("cg-rvk", "沪A-1001");
        reserve(no, "cg-rvk-res", 1, "ESC-B",
                LocalDateTime.parse("2026-10-02T15:30:00"), LocalDateTime.parse("2026-10-02T16:00:00"));
        // 段已开始打卡（OUT）：资格撤销不连带释放该预约，但 IN 重查资格拒绝。
        relayCheckin(no, "cg-rvk-out", "ESC-01", "OUT", LocalDateTime.parse("2026-10-02T23:40:00"));
        assertThat(revokeQualification(QUAL_ESC_B, "cg-rvk-rv", 1).httpStatus()).isEqualTo(200);
        assertThatThrownBy(() -> relayCheckin(no, "cg-rvk-in", "ESC-B", "IN",
                LocalDateTime.parse("2026-10-02T23:45:00")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("QUALIFICATION_REVOKED"));
        assertThat(segmentOf(no).get("status")).isEqualTo("IN_PROGRESS");
    }

    @Test
    void revokeAfterCompletedHandoverKeepsHandoverAndLeavesRiskEvidence() {
        String no = dispatchedControlled("cg-done", "沪A-1001");
        reserve(no, "cg-done-res", 1, "ESC-B",
                LocalDateTime.parse("2026-10-02T15:30:00"), LocalDateTime.parse("2026-10-02T16:00:00"));
        relayCheckin(no, "cg-done-out", "ESC-01", "OUT", LocalDateTime.parse("2026-10-02T23:40:00"));
        relayCheckin(no, "cg-done-in", "ESC-B", "IN", LocalDateTime.parse("2026-10-02T23:45:00"));
        assertThat(segmentOf(no).get("status")).isEqualTo("COMPLETED");

        // 换班完成后撤销资格：已成功换班不回滚，留风险证据，并拒绝该人后续预约。
        ServiceResult revoked = revokeQualification(QUAL_ESC_B, "cg-done-rv", 1);
        assertThat(revoked.httpStatus()).isEqualTo(200);
        assertThat(revoked.body().get("revoked")).isEqualTo(true);
        assertThat(revoked.body().get("versionNo")).isEqualTo(2);
        assertThat(revoked.body().get("completedHandoversKept")).isEqualTo(1);
        Map<String, Object> segment = segmentOf(no);
        assertThat(segment.get("status")).isEqualTo("COMPLETED");
        assertThat(segment.get("inEscortId")).isEqualTo("ESC-B");
        assertThat(events(view(no)).stream()
                .anyMatch(e -> "QUALIFICATION_RISK".equals(e.get("eventType")))).isTrue();
        // 已兑现预约不连带释放（保留为已履行占用证据）。
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> reservations =
                (java.util.List<Map<String, Object>>) listReservations(no).body().get("reservations");
        assertThat(reservations.get(0).get("status")).isEqualTo("ACTIVE");

        // 拒绝该人后续预约。
        String no2 = dispatchedControlled("cg-done2", "沪A-1002");
        assertThatThrownBy(() -> reserve(no2, "cg-done2-res", 1, "ESC-B",
                LocalDateTime.parse("2026-10-02T15:30:00"), LocalDateTime.parse("2026-10-02T16:00:00")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("QUALIFICATION_REVOKED"));
    }

    @Test
    void uncontrolledSegmentKeepsLegacyBehavior() {
        // 未启用管控的旧接力段：无需预约，IN 直接成功（原行为）。
        String no = shipmentNo(apply("cg-old-apply", "沪A-1002", "FLAMMABLE", "R-G3", DEPARTURE));
        approve(no);
        dispatch(no, "cg-old-dispatch");
        assertThat(setRelayPoint(no, "cg-old-rp", 1).httpStatus()).isEqualTo(200);
        relayCheckin(no, "cg-old-out", "ESC-01", "OUT", LocalDateTime.parse("2026-10-02T23:40:00"));
        ServiceResult in = relayCheckin(no, "cg-old-in", "ESC-02", "IN",
                LocalDateTime.parse("2026-10-02T23:45:00"));
        assertThat(in.body().get("handoverCompleted")).isEqualTo(true);
        assertThat(segmentOf(no).get("status")).isEqualTo("COMPLETED");
    }
}
