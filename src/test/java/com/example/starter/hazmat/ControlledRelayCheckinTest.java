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
 * 受控接力段 IN 门禁：必须来自预约人员；实际接班时刻落在班段 [start,end) 内（恰到 end 拒绝）；
 * 重查资格未撤销；原双人换班、禁行与路线核准规则不变；新门禁不阻止原人员 OUT 离岗。
 *
 * <p>班段 [15:30,15:50) UTC = [23:30,23:50) Asia/Shanghai；禁行起点 2026-10-03 00:00。
 */
class ControlledRelayCheckinTest extends HazmatIntegrationTestBase {

    private static final LocalDateTime DEPARTURE = LocalDateTime.parse("2026-10-02T22:30:00");
    private static final LocalDateTime QUAL_FROM = LocalDateTime.parse("2026-10-02T14:00:00");
    private static final LocalDateTime QUAL_TO = LocalDateTime.parse("2026-10-03T03:00:00");
    private static final LocalDateTime SHIFT_START = LocalDateTime.parse("2026-10-02T15:30:00");
    private static final LocalDateTime SHIFT_END = LocalDateTime.parse("2026-10-02T15:50:00");
    private static final LocalDateTime OUT_TIME = LocalDateTime.parse("2026-10-02T23:40:00");
    private static final LocalDateTime IN_TIME = LocalDateTime.parse("2026-10-02T23:45:00");

    @BeforeEach
    void fixClock() {
        clock.setTime(LocalDateTime.parse("2026-10-02T22:30:00"));
    }

    private String dispatched(String reqPrefix, String plate) {
        String no = shipmentNo(apply(reqPrefix + "-apply", plate, "FLAMMABLE", "R-G3", DEPARTURE));
        approve(no);
        dispatch(no, reqPrefix + "-dispatch");
        return no;
    }

    private long controlledSegment(String shipmentNo, String reqPrefix) {
        ServiceResult set = setRelayPoint(shipmentNo, reqPrefix + "-rp", 1, true);
        return ((Number) set.body().get("relaySegmentId")).longValue();
    }

    private long qualifyEscB(String reqPrefix) {
        return ((Number) createQualification(reqPrefix + "-qual", "ESC-B", "FLAMMABLE",
                QUAL_FROM, QUAL_TO).body().get("qualificationId")).longValue();
    }

    private long reserveEscB(String shipmentNo, long segmentId, String reqPrefix) {
        return ((Number) createReservation(shipmentNo, segmentId, reqPrefix + "-res", "ESC-B",
                SHIFT_START, SHIFT_END).body().get("reservationId")).longValue();
    }

    private Map<String, Object> segmentOf(String shipmentNo, int relaySeq) {
        return relaySegments(view(shipmentNo)).stream()
                .filter(s -> Integer.valueOf(relaySeq).equals(s.get("relaySeq")))
                .findFirst().orElseThrow();
    }

    @Test
    void controlledHandoverCompletesAndConsumesReservation() {
        qualifyEscB("ci-ok");
        String no = dispatched("ci-ok", "沪A-1001");
        long seg = controlledSegment(no, "ci-ok");
        reserveEscB(no, seg, "ci-ok");
        assertThat(segmentOf(no, 1).get("personnelControlled")).isEqualTo(true);

        // 原人员 OUT 离岗不受新门禁阻止。
        relayCheckin(no, "ci-ok-out", "ESC-01", "OUT", OUT_TIME);
        // 预约人员在班段内 IN → 换班完成，预约核销（CONSUMED，版本加 1）。
        ServiceResult in = relayCheckin(no, "ci-ok-in", "ESC-B", "IN", IN_TIME);
        assertThat(in.body().get("handoverCompleted")).isEqualTo(true);
        Map<String, Object> segment = segmentOf(no, 1);
        assertThat(segment.get("status")).isEqualTo("COMPLETED");
        assertThat(segment.get("inEscortId")).isEqualTo("ESC-B");
        Map<String, Object> reservation = reservations(view(no)).get(0);
        assertThat(reservation.get("status")).isEqualTo("CONSUMED");
        assertThat(reservation.get("versionNo")).isEqualTo(2);
        assertThat(reservation.get("qualificationVersion")).isEqualTo(1);
    }

    @Test
    void inFromNonReservedPersonRejected() {
        qualifyEscB("ci-who");
        String no = dispatched("ci-who", "沪A-1001");
        long seg = controlledSegment(no, "ci-who");
        reserveEscB(no, seg, "ci-who");
        relayCheckin(no, "ci-who-out", "ESC-01", "OUT", OUT_TIME);
        // 非预约人员 IN → 拒绝，换班不生效。
        assertThatThrownBy(() -> relayCheckin(no, "ci-who-in", "ESC-99", "IN", IN_TIME))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("RELAY_IN_NOT_RESERVED"));
        assertThat(segmentOf(no, 1).get("status")).isEqualTo("IN_PROGRESS");
        // 预约人员 IN 仍可完成。
        relayCheckin(no, "ci-who-in2", "ESC-B", "IN", IN_TIME);
        assertThat(segmentOf(no, 1).get("status")).isEqualTo("COMPLETED");
    }

    @Test
    void inOutsideShiftRejectedIncludingExactEnd() {
        qualifyEscB("ci-shift");
        String no = dispatched("ci-shift", "沪A-1001");
        long seg = controlledSegment(no, "ci-shift");
        reserveEscB(no, seg, "ci-shift");
        relayCheckin(no, "ci-shift-out", "ESC-01", "OUT", OUT_TIME);
        // 班段前：23:25 CST = 15:25 UTC < 15:30 → 拒绝。
        assertThatThrownBy(() -> relayCheckin(no, "ci-shift-early", "ESC-B", "IN",
                LocalDateTime.parse("2026-10-02T23:25:00")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("RELAY_IN_OUT_OF_SHIFT"));
        // 恰到 end：23:50 CST = 15:50 UTC == shiftEnd → 拒绝。
        assertThatThrownBy(() -> relayCheckin(no, "ci-shift-end", "ESC-B", "IN",
                LocalDateTime.parse("2026-10-02T23:50:00")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("RELAY_IN_OUT_OF_SHIFT"));
        // 班段内 23:45 → 完成。
        relayCheckin(no, "ci-shift-ok", "ESC-B", "IN", IN_TIME);
        assertThat(segmentOf(no, 1).get("status")).isEqualTo("COMPLETED");
    }

    @Test
    void inAfterQualificationRevokedRejected() {
        long qid = qualifyEscB("ci-rvk");
        String no = dispatched("ci-rvk", "沪A-1001");
        long seg = controlledSegment(no, "ci-rvk");
        reserveEscB(no, seg, "ci-rvk");
        relayCheckin(no, "ci-rvk-out", "ESC-01", "OUT", OUT_TIME);
        // 预约后、IN 前资格被撤销 → IN 重查拒绝，预约仍 ACTIVE，换班未生效。
        revokeQualification(qid, "ci-rvk-qr", 1);
        assertThatThrownBy(() -> relayCheckin(no, "ci-rvk-in", "ESC-B", "IN", IN_TIME))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("QUALIFICATION_REVOKED"));
        assertThat(segmentOf(no, 1).get("status")).isEqualTo("IN_PROGRESS");
        assertThat(reservations(view(no)).get(0).get("status")).isEqualTo("ACTIVE");
    }

    @Test
    void uncontrolledSegmentKeepsLegacyBehavior() {
        qualifyEscB("ci-old");
        String no = dispatched("ci-old", "沪A-1001");
        // 旧接力段（未启用管控）：无需预约，任意接班员双人打卡即完成。
        setRelayPoint(no, "ci-old-rp", 1);
        assertThat(segmentOf(no, 1).get("personnelControlled")).isEqualTo(false);
        relayCheckin(no, "ci-old-out", "ESC-01", "OUT", OUT_TIME);
        ServiceResult in = relayCheckin(no, "ci-old-in", "ESC-ANY", "IN", IN_TIME);
        assertThat(in.body().get("handoverCompleted")).isEqualTo(true);
        assertThat(segmentOf(no, 1).get("status")).isEqualTo("COMPLETED");
        assertThat(reservations(view(no))).isEmpty();
    }

    @Test
    void curfewAndDoublePersonRulesStillApplyOnControlledSegment() {
        qualifyEscB("ci-rule");
        String no = dispatched("ci-rule", "沪A-1001");
        long seg = controlledSegment(no, "ci-rule");
        reserveEscB(no, seg, "ci-rule");
        // 双人规则：预约人员与自己交班（同人 OUT+IN）仍拒绝。
        relayCheckin(no, "ci-rule-out", "ESC-B", "OUT", OUT_TIME);
        assertThatThrownBy(() -> relayCheckin(no, "ci-rule-in", "ESC-B", "IN", IN_TIME))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("RELAY_SAME_ESCORT"));
        // 禁行规则：恰满禁行起点仍未完成换班 → 懒冻结（409 结果），运单转终态。
        clock.setTime(LocalDateTime.parse("2026-10-03T00:00:00"));
        ServiceResult frozen = relayCheckin(no, "ci-rule-in2", "ESC-02", "IN",
                LocalDateTime.parse("2026-10-02T23:45:00"));
        assertThat(frozen.httpStatus()).isEqualTo(409);
        assertThat(frozen.body().get("errorCode")).isEqualTo("CURFEW_FROZEN");
        assertThat(view(no).get("status")).isEqualTo("CURFEW_FROZEN");
        // 运单终结释放未结束预约。
        assertThat(reservations(view(no)).get(0).get("status")).isEqualTo("RELEASED");
    }
}
