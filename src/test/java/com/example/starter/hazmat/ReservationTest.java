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
 * 人员资格预约：两张合成运单都打算在 23:45（Asia/Shanghai）由 ESC-B 接班，
 * 同一人不能在重叠班段同时接下两张单；预约冲突的那张仍由原押运员负责。
 *
 * <p>场景：R-G3 夜间禁行 2026-10-03 00:00~05:00；运单 2026-10-02 22:30 发运，
 * 途经点 p1 计划 23:30，启用人员管控；班段 [15:30,16:00) UTC = [23:30,24:00) Asia/Shanghai。
 */
class ReservationTest extends HazmatIntegrationTestBase {

    private static final LocalDateTime DEPARTURE = LocalDateTime.parse("2026-10-02T22:30:00");
    private static final LocalDateTime QUAL_FROM = LocalDateTime.parse("2026-10-02T14:00:00");
    private static final LocalDateTime QUAL_TO = LocalDateTime.parse("2026-10-03T03:00:00");
    private static final LocalDateTime SHIFT_START = LocalDateTime.parse("2026-10-02T15:30:00");
    private static final LocalDateTime SHIFT_END = LocalDateTime.parse("2026-10-02T16:00:00");

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
        assertThat(set.httpStatus()).isEqualTo(200);
        return ((Number) set.body().get("relaySegmentId")).longValue();
    }

    private void qualifyEscB(String reqPrefix) {
        createQualification(reqPrefix + "-qual", "ESC-B", "FLAMMABLE", QUAL_FROM, QUAL_TO);
    }

    private Map<String, Object> reservationOf(String shipmentNo) {
        return reservations(view(shipmentNo)).stream().findFirst().orElseThrow();
    }

    @Test
    void twoShipmentsCompetingForSameEscortOnlyOneReserves() {
        qualifyEscB("rv-main");
        String noA = dispatched("rv-main-a", "沪A-1001");
        String noB = dispatched("rv-main-b", "沪A-1002");
        long segA = controlledSegment(noA, "rv-main-a");
        long segB = controlledSegment(noB, "rv-main-b");

        // 第一张单预约 ESC-B 班段 [15:30,16:00) UTC 成功，资格版本证据落库。
        ServiceResult resA = createReservation(noA, segA, "rv-main-a-res", "ESC-B",
                SHIFT_START, SHIFT_END);
        assertThat(resA.httpStatus()).isEqualTo(200);
        Map<String, Object> evidence = reservationOf(noA);
        assertThat(evidence.get("escortId")).isEqualTo("ESC-B");
        assertThat(evidence.get("status")).isEqualTo("ACTIVE");
        assertThat(evidence.get("versionNo")).isEqualTo(1);
        assertThat(evidence.get("shiftStartUtc")).isEqualTo("2026-10-02T15:30");
        assertThat(evidence.get("shiftEndUtc")).isEqualTo("2026-10-02T16:00");
        assertThat(evidence.get("qualificationVersion")).isEqualTo(1);

        // 第二张单同员同班段 → 内部重叠拒绝，不产生占用。
        assertThatThrownBy(() -> createReservation(noB, segB, "rv-main-b-res", "ESC-B",
                SHIFT_START, SHIFT_END))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("ESCORT_SHIFT_OVERLAP"));
        assertThat(reservations(view(noB))).isEmpty();

        // 预约冲突的那张仍由原押运员负责：OUT 离岗不被新门禁阻止，
        // 但没有人员占用就不允许记录换班完成（IN 被拒绝，换班不生效）。
        ServiceResult out = relayCheckin(noB, "rv-main-b-out", "ESC-01", "OUT",
                LocalDateTime.parse("2026-10-02T23:40:00"));
        assertThat(out.body().get("handoverCompleted")).isEqualTo(false);
        assertThatThrownBy(() -> relayCheckin(noB, "rv-main-b-in", "ESC-B", "IN",
                LocalDateTime.parse("2026-10-02T23:45:00")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("RESERVATION_REQUIRED"));
        Map<String, Object> segBView = relaySegments(view(noB)).stream()
                .filter(s -> Integer.valueOf(1).equals(s.get("relaySeq"))).findFirst().orElseThrow();
        assertThat(segBView.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(segBView.get("inEscortId")).isNull();
    }

    @Test
    void adjacentShiftsAllowedButInternalOverlapRejected() {
        qualifyEscB("rv-adj");
        String noA = dispatched("rv-adj-a", "沪A-1001");
        String noB = dispatched("rv-adj-b", "沪A-1002");
        long segA = controlledSegment(noA, "rv-adj-a");
        long segB = controlledSegment(noB, "rv-adj-b");
        createReservation(noA, segA, "rv-adj-a-res", "ESC-B", SHIFT_START, SHIFT_END);

        // 端点相接 [16:00,16:30) 可预约。
        ServiceResult adjacent = createReservation(noB, segB, "rv-adj-b-res", "ESC-B",
                LocalDateTime.parse("2026-10-02T16:00:00"), LocalDateTime.parse("2026-10-02T16:30:00"));
        assertThat(adjacent.httpStatus()).isEqualTo(200);
        // 撤销后内部重叠 [15:45,16:15) 拒绝。
        String noC = dispatched("rv-adj-c", "沪B-2001");
        long segC = controlledSegment(noC, "rv-adj-c");
        assertThatThrownBy(() -> createReservation(noC, segC, "rv-adj-c-res", "ESC-B",
                LocalDateTime.parse("2026-10-02T15:45:00"), LocalDateTime.parse("2026-10-02T16:15:00")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("ESCORT_SHIFT_OVERLAP"));
    }

    @Test
    void categoryMismatchRevokedAndUncoveredShiftRejected() {
        // 货类不符：ESC-C 只有 TOXIC 资格。
        createQualification("rv-mm-q", "ESC-C", "TOXIC", QUAL_FROM, QUAL_TO);
        String no = dispatched("rv-mm", "沪A-1001");
        long seg = controlledSegment(no, "rv-mm");
        assertThatThrownBy(() -> createReservation(no, seg, "rv-mm-res", "ESC-C",
                SHIFT_START, SHIFT_END))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("QUALIFICATION_CATEGORY_MISMATCH"));

        // 已撤销资格拒绝。
        long qid = ((Number) createQualification("rv-rvk-q", "ESC-D", "FLAMMABLE", QUAL_FROM, QUAL_TO)
                .body().get("qualificationId")).longValue();
        revokeQualification(qid, "rv-rvk-qr", 1);
        assertThatThrownBy(() -> createReservation(no, seg, "rv-rvk-res", "ESC-D",
                SHIFT_START, SHIFT_END))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("QUALIFICATION_REVOKED"));

        // 班段未被资格区间完整覆盖。
        createQualification("rv-cov-q", "ESC-E", "FLAMMABLE",
                QUAL_FROM, LocalDateTime.parse("2026-10-02T15:45:00"));
        assertThatThrownBy(() -> createReservation(no, seg, "rv-cov-res", "ESC-E",
                SHIFT_START, SHIFT_END))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("SHIFT_NOT_COVERED"));

        // 班段起止无序。
        assertThatThrownBy(() -> createReservation(no, seg, "rv-ord-res", "ESC-E",
                SHIFT_END, SHIFT_START))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("SHIFT_INTERVAL_INVALID"));
        // 失败不占键：修正后同键成功。
        createQualification("rv-ord-q", "ESC-E2", "FLAMMABLE", QUAL_FROM, QUAL_TO);
        assertThat(createReservation(no, seg, "rv-ord-res", "ESC-E2", SHIFT_START, SHIFT_END)
                .httpStatus()).isEqualTo(200);
    }

    @Test
    void crossShipmentSegmentAndUncontrolledSegmentRejected() {
        qualifyEscB("rv-x");
        String noA = dispatched("rv-x-a", "沪A-1001");
        String noB = dispatched("rv-x-b", "沪A-1002");
        long segA = controlledSegment(noA, "rv-x-a");
        // 跨运单接力点：用 A 的接力段给 B 预约。
        assertThatThrownBy(() -> createReservation(noB, segA, "rv-x-res", "ESC-B",
                SHIFT_START, SHIFT_END))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("RELAY_SEGMENT_MISMATCH"));
        // 未启用管控的旧接力段不接受预约。
        ServiceResult plain = setRelayPoint(noB, "rv-x-b-rp0", 0);
        long plainSeg = ((Number) plain.body().get("relaySegmentId")).longValue();
        assertThatThrownBy(() -> createReservation(noB, plainSeg, "rv-x-res2", "ESC-B",
                SHIFT_START, SHIFT_END))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("RELAY_SEGMENT_NOT_CONTROLLED"));
    }

    @Test
    void revokeReservationBeforeCheckinReleasesOccupation() {
        qualifyEscB("rv-rv");
        String noA = dispatched("rv-rv-a", "沪A-1001");
        String noB = dispatched("rv-rv-b", "沪A-1002");
        long segA = controlledSegment(noA, "rv-rv-a");
        long segB = controlledSegment(noB, "rv-rv-b");
        long resA = ((Number) createReservation(noA, segA, "rv-rv-a-res", "ESC-B",
                SHIFT_START, SHIFT_END).body().get("reservationId")).longValue();

        // 版本过期 409。
        assertThatThrownBy(() -> revokeReservation(noA, segA, resA, "rv-rv-stale", 5))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("VERSION_CONFLICT"));
        // 首次换班打卡前撤销成功：状态 RELEASED，版本加 1，班段占用释放。
        ServiceResult revoked = revokeReservation(noA, segA, resA, "rv-rv-ok", 1);
        assertThat(revoked.body().get("reservationReleased")).isEqualTo(true);
        Map<String, Object> evidence = reservationOf(noA);
        assertThat(evidence.get("status")).isEqualTo("RELEASED");
        assertThat(evidence.get("versionNo")).isEqualTo(2);
        // 占用已释放：ESC-B 同班段可改接另一张单。
        assertThat(createReservation(noB, segB, "rv-rv-b-res", "ESC-B", SHIFT_START, SHIFT_END)
                .httpStatus()).isEqualTo(200);
        // 已释放的预约不可再撤销。
        assertThatThrownBy(() -> revokeReservation(noA, segA, resA, "rv-rv-again", 2))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("RESERVATION_NOT_ACTIVE"));
    }

    @Test
    void revokeReservationAfterCheckinStartedRejected() {
        qualifyEscB("rv-lk");
        String no = dispatched("rv-lk", "沪A-1001");
        long seg = controlledSegment(no, "rv-lk");
        long res = ((Number) createReservation(no, seg, "rv-lk-res", "ESC-B",
                SHIFT_START, SHIFT_END).body().get("reservationId")).longValue();
        // 原人员 OUT 打卡后（换班已开始），预约不得撤销。
        relayCheckin(no, "rv-lk-out", "ESC-01", "OUT", LocalDateTime.parse("2026-10-02T23:40:00"));
        assertThatThrownBy(() -> revokeReservation(no, seg, res, "rv-lk-rv", 1))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("RESERVATION_LOCKED"));
        assertThat(reservationOf(no).get("status")).isEqualTo("ACTIVE");
    }

    @Test
    void shipmentTerminalReleasesReservation() {
        qualifyEscB("rv-tm");
        String no = dispatched("rv-tm", "沪A-1001");
        long seg = controlledSegment(no, "rv-tm");
        createReservation(no, seg, "rv-tm-res", "ESC-B", SHIFT_START, SHIFT_END);
        // 运单核销终结 → 未结束预约释放。
        complete(no, "rv-tm-complete");
        Map<String, Object> evidence = reservationOf(no);
        assertThat(evidence.get("status")).isEqualTo("RELEASED");
        assertThat(evidence.get("versionNo")).isEqualTo(2);
    }

    @Test
    void reservationEndpointsAreIdempotent() {
        qualifyEscB("rv-idem");
        String no = dispatched("rv-idem", "沪A-1001");
        long seg = controlledSegment(no, "rv-idem");
        // 同键同参重放首次响应；同键异参 409。
        ServiceResult first = createReservation(no, seg, "rv-idem-res", "ESC-B", SHIFT_START, SHIFT_END);
        ServiceResult replay = createReservation(no, seg, "rv-idem-res", "ESC-B", SHIFT_START, SHIFT_END);
        assertThat(String.valueOf(replay.body().get("reservationId")))
                .isEqualTo(String.valueOf(first.body().get("reservationId")));
        assertThat(reservations(view(no))).hasSize(1);
        assertThatThrownBy(() -> createReservation(no, seg, "rv-idem-res", "ESC-B",
                SHIFT_START, LocalDateTime.parse("2026-10-02T16:30:00")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("IDEMPOTENT_FINGERPRINT_MISMATCH"));
        // 同一接力段不可重复占用。
        assertThatThrownBy(() -> createReservation(no, seg, "rv-idem-res2", "ESC-B",
                LocalDateTime.parse("2026-10-02T16:30:00"), LocalDateTime.parse("2026-10-02T17:00:00")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("SEGMENT_ALREADY_RESERVED"));
    }
}
