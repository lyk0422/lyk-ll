package com.example.starter.hazmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.starter.hazmat.api.ApiException;
import com.example.starter.hazmat.service.ServiceResult;

/**
 * 人员资格预约：班段起止有序与资格区间覆盖、端点相接可预约/内部重叠拒绝、
 * 货类不符/跨运单接力点/已撤销资格拒绝、撤销预约与版本、运单终结释放未结束预约。
 *
 * <p>场景：R-G3 夜间禁行 2026-10-03 00:00~05:00（上海）；运单 2026-10-02 22:30 发运。
 * 班段 [15:30,16:00) UTC = 23:30~24:00 上海，覆盖 23:45 接班时刻（15:45 UTC）。
 */
class ReservationTest extends HazmatIntegrationTestBase {

    private static final LocalDateTime DEPARTURE = LocalDateTime.parse("2026-10-02T22:30:00");
    private static final LocalDateTime CURFEW_START = LocalDateTime.parse("2026-10-03T00:00:00");
    /** 班段（UTC）：23:30~24:00 上海。 */
    private static final LocalDateTime SHIFT_START = LocalDateTime.parse("2026-10-02T15:30:00");
    private static final LocalDateTime SHIFT_END = LocalDateTime.parse("2026-10-02T16:00:00");
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> reservationOf(ServiceResult result) {
        return (Map<String, Object>) result.body().get("reservation");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> reservationsOf(String shipmentNo) {
        return (List<Map<String, Object>>) listReservations(shipmentNo).body().get("reservations");
    }

    @Test
    void reserveSuccessExposesShiftAndQualificationVersionEvidence() {
        String no = dispatchedControlled("rs-ok", "沪A-1001");
        ServiceResult reserved = reserve(no, "rs-ok-res", 1, "ESC-B", SHIFT_START, SHIFT_END);
        assertThat(reserved.httpStatus()).isEqualTo(200);
        Map<String, Object> reservation = reservationOf(reserved);
        assertThat(reservation.get("escortId")).isEqualTo("ESC-B");
        assertThat(reservation.get("shiftStart")).isEqualTo("2026-10-02T15:30");
        assertThat(reservation.get("shiftEnd")).isEqualTo("2026-10-02T16:00");
        assertThat(reservation.get("status")).isEqualTo("ACTIVE");
        assertThat(reservation.get("versionNo")).isEqualTo(1);
        // 班段及资格版本证据查询。
        assertThat(reservation.get("qualificationId")).isEqualTo(2L);
        assertThat(reservation.get("qualificationVersion")).isEqualTo(1);
        List<Map<String, Object>> listed = reservationsOf(no);
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("reservationId")).isEqualTo(reservation.get("reservationId"));
        assertThat(events(view(no)).stream()
                .anyMatch(e -> "RESERVATION_CREATED".equals(e.get("eventType")))).isTrue();
    }

    @Test
    void shiftMustBeOrderedAndCoveredByQualification() {
        String no = dispatchedControlled("rs-shift", "沪A-1001");
        // 起止无序：终点恰等于起点拒绝。
        assertThatThrownBy(() -> reserve(no, "rs-shift-eq", 1, "ESC-B", SHIFT_START, SHIFT_START))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("RESERVATION_SHIFT_INVALID"));
        // 班段超出资格区间（资格 ESC-B 有效至 2026-10-03 00:00 UTC）：拒绝。
        assertThatThrownBy(() -> reserve(no, "rs-shift-out", 1, "ESC-B",
                SHIFT_START, LocalDateTime.parse("2026-10-03T00:30:00")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("RESERVATION_SHIFT_NOT_COVERED"));
        // 班段起点早于资格起点：拒绝。
        assertThatThrownBy(() -> reserve(no, "rs-shift-early", 1, "ESC-B",
                LocalDateTime.parse("2026-10-01T23:00:00"), LocalDateTime.parse("2026-10-02T15:00:00")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("RESERVATION_SHIFT_NOT_COVERED"));
        // 失败不占键：同键改参（合法班段）可成功。
        assertThat(reserve(no, "rs-shift-out", 1, "ESC-B", SHIFT_START, SHIFT_END).httpStatus())
                .isEqualTo(200);
    }

    @Test
    void categoryMismatchAndRevokedQualificationRejected() {
        String no = dispatchedControlled("rs-cat", "沪A-1001");
        // 货类不符：ESC-D 仅可押运毒害品，运单为易燃液体。
        assertThatThrownBy(() -> reserve(no, "rs-cat-mis", 1, "ESC-D", SHIFT_START, SHIFT_END))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("QUALIFICATION_CATEGORY_MISMATCH"));
        // 已撤销资格拒绝。
        assertThat(revokeQualification(QUAL_ESC_B, "rs-cat-rv", 1).httpStatus()).isEqualTo(200);
        assertThatThrownBy(() -> reserve(no, "rs-cat-rvd", 1, "ESC-B", SHIFT_START, SHIFT_END))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("QUALIFICATION_REVOKED"));
        // 无资格人员：货类不符（无任何该货类资格）。
        assertThatThrownBy(() -> reserve(no, "rs-cat-none", 1, "ESC-ZZ", SHIFT_START, SHIFT_END))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("QUALIFICATION_CATEGORY_MISMATCH"));
    }

    @Test
    void crossShipmentRelayPointAndUncontrolledSegmentRejected() {
        String no1 = dispatchedControlled("rs-x1", "沪A-1001");
        // 运单2 未设置任何接力段：途经点 1 的接力段属于运单1，跨运单接力点拒绝。
        String no2 = shipmentNo(apply("rs-x2-apply", "沪A-1002", "FLAMMABLE", "R-G3", DEPARTURE));
        approve(no2);
        dispatch(no2, "rs-x2-dispatch");
        assertThatThrownBy(() -> reserve(no2, "rs-x2-res", 1, "ESC-B", SHIFT_START, SHIFT_END))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("RESERVATION_SEGMENT_MISMATCH"));
        // 未启用管控的旧接力段：无需预约，拒绝预约请求。
        assertThat(setRelayPoint(no2, "rs-x2-rp", 1).httpStatus()).isEqualTo(200);
        assertThatThrownBy(() -> reserve(no2, "rs-x2-res2", 1, "ESC-B", SHIFT_START, SHIFT_END))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("RELAY_SEGMENT_UNCONTROLLED"));
        // 运单1 正常预约不受影响。
        assertThat(reserve(no1, "rs-x1-res", 1, "ESC-B", SHIFT_START, SHIFT_END).httpStatus())
                .isEqualTo(200);
    }

    @Test
    void twoShipmentsContendForSameEscortAndLoserKeepsOriginalEscort() {
        // 两张合成运单都打算 23:45（上海）由 ESC-B 接班：班段同为 [15:30,16:00) UTC。
        String no1 = dispatchedControlled("rs-duel1", "沪A-1001");
        String no2 = dispatchedControlled("rs-duel2", "沪A-1002");

        // 第一张预约成功，人员占用随校验同一事务提交。
        assertThat(reserve(no1, "rs-duel1-res", 1, "ESC-B", SHIFT_START, SHIFT_END).httpStatus())
                .isEqualTo(200);
        // 第二张同人同段：内部重叠拒绝，同一人不能在重叠班段同时接下两张单。
        assertThatThrownBy(() -> reserve(no2, "rs-duel2-res", 1, "ESC-B", SHIFT_START, SHIFT_END))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("RESERVATION_OVERLAP"));
        // 部分重叠同样拒绝。
        assertThatThrownBy(() -> reserve(no2, "rs-duel2-part", 1, "ESC-B",
                LocalDateTime.parse("2026-10-02T15:00:00"), LocalDateTime.parse("2026-10-02T15:45:00")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("RESERVATION_OVERLAP"));
        // 预约冲突的那张仍由原押运员负责：无人员占用、无换班记录，接力段保持进行中。
        Map<String, Object> segment2 = relaySegments(view(no2)).get(0);
        assertThat(segment2.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(segment2.get("inEscortId")).isNull();
        assertThat(reservationsOf(no2)).isEmpty();

        // 端点相接可预约：第二张改约 ESC-B 邻接班段 [16:00,17:00) UTC。
        ServiceResult adjacent = reserve(no2, "rs-duel2-adj", 1, "ESC-B",
                LocalDateTime.parse("2026-10-02T16:00:00"), LocalDateTime.parse("2026-10-02T17:00:00"));
        assertThat(adjacent.httpStatus()).isEqualTo(200);

        // 第一张 23:45 由 ESC-B 正常接班完成换班。
        relayCheckin(no1, "rs-duel1-out", "ESC-01", "OUT", LocalDateTime.parse("2026-10-02T23:40:00"));
        ServiceResult in = relayCheckin(no1, "rs-duel1-in", "ESC-B", "IN",
                LocalDateTime.parse("2026-10-02T23:45:00"));
        assertThat(in.body().get("handoverCompleted")).isEqualTo(true);

        // 第二张释放邻接预约后改约 ESC-C 同班段，换班完成。
        long adjId = ((Number) reservationOf(adjacent).get("reservationId")).longValue();
        assertThat(releaseReservation(no2, adjId, "rs-duel2-rel", 1).httpStatus()).isEqualTo(200);
        assertThat(reserve(no2, "rs-duel2-esc", 1, "ESC-C", SHIFT_START, SHIFT_END).httpStatus())
                .isEqualTo(200);
        relayCheckin(no2, "rs-duel2-out", "ESC-09", "OUT", LocalDateTime.parse("2026-10-02T23:40:00"));
        ServiceResult in2 = relayCheckin(no2, "rs-duel2-in", "ESC-C", "IN",
                LocalDateTime.parse("2026-10-02T23:45:00"));
        assertThat(in2.body().get("handoverCompleted")).isEqualTo(true);
    }

    @Test
    void releaseReservationVersionRulesAndCheckinStartedLock() {
        String no = dispatchedControlled("rs-rel", "沪A-1001");
        long id = ((Number) reservationOf(reserve(no, "rs-rel-res", 1, "ESC-A", SHIFT_START, SHIFT_END))
                .get("reservationId")).longValue();
        // 版本过期：409。
        assertThatThrownBy(() -> releaseReservation(no, id, "rs-rel-stale", 7))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("VERSION_CONFLICT"));
        // 首次换班打卡前可撤销并释放：版本加 1。
        ServiceResult released = releaseReservation(no, id, "rs-rel-ok", 1);
        assertThat(released.httpStatus()).isEqualTo(200);
        assertThat(reservationOf(released).get("status")).isEqualTo("RELEASED");
        assertThat(reservationOf(released).get("versionNo")).isEqualTo(2);
        // 重复撤销：409。
        assertThatThrownBy(() -> releaseReservation(no, id, "rs-rel-again", 2))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("RESERVATION_ALREADY_RELEASED"));
        // 释放后人员占用解除：可再约同人同班段。
        long id2 = ((Number) reservationOf(reserve(no, "rs-rel-res2", 1, "ESC-A",
                SHIFT_START, SHIFT_END)).get("reservationId")).longValue();
        // 已开始打卡（OUT 已打）则不得撤销。
        relayCheckin(no, "rs-rel-out", "ESC-01", "OUT", LocalDateTime.parse("2026-10-02T23:40:00"));
        assertThatThrownBy(() -> releaseReservation(no, id2, "rs-rel-late", 1))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("RESERVATION_CHECKIN_STARTED"));
        // 预约仍生效，IN 可正常完成换班。
        ServiceResult in = relayCheckin(no, "rs-rel-in", "ESC-A", "IN",
                LocalDateTime.parse("2026-10-02T23:45:00"));
        assertThat(in.body().get("handoverCompleted")).isEqualTo(true);
    }

    @Test
    void terminalShipmentReleasesUnfinishedReservations() {
        // 运单1：预约后未打卡即核销 → 未结束预约被释放。
        String no1 = dispatchedControlled("rs-term1", "沪A-1001");
        reserve(no1, "rs-term1-res", 1, "ESC-A", SHIFT_START, SHIFT_END);
        assertThat(complete(no1, "rs-term1-complete").httpStatus()).isEqualTo(200);
        Map<String, Object> r1 = reservationsOf(no1).get(0);
        assertThat(r1.get("status")).isEqualTo("RELEASED");
        assertThat(r1.get("versionNo")).isEqualTo(2);
        assertThat(r1.get("releasedAt")).isNotNull();

        // 运单2：换班已完成（预约已兑现）→ 核销后保留为已履行占用证据。
        String no2 = dispatchedControlled("rs-term2", "沪A-1002");
        reserve(no2, "rs-term2-res", 1, "ESC-B", SHIFT_START, SHIFT_END);
        relayCheckin(no2, "rs-term2-out", "ESC-01", "OUT", LocalDateTime.parse("2026-10-02T23:40:00"));
        relayCheckin(no2, "rs-term2-in", "ESC-B", "IN", LocalDateTime.parse("2026-10-02T23:45:00"));
        assertThat(complete(no2, "rs-term2-complete").httpStatus()).isEqualTo(200);
        Map<String, Object> r2 = reservationsOf(no2).get(0);
        assertThat(r2.get("status")).isEqualTo("ACTIVE");
        assertThat(r2.get("versionNo")).isEqualTo(1);
    }

    @Test
    void curfewFreezeReleasesUnfinishedReservation() {
        String no = dispatchedControlled("rs-frz", "沪A-1001");
        reserve(no, "rs-frz-res", 1, "ESC-A", SHIFT_START, SHIFT_END);
        // 禁行起点到达仍未完成换班：扫描冻结并转处置，未结束预约同步释放。
        scan(CURFEW_START);
        assertThat(view(no).get("status")).isEqualTo("CURFEW_FROZEN");
        Map<String, Object> reservation = reservationsOf(no).get(0);
        assertThat(reservation.get("status")).isEqualTo("RELEASED");
        // 终态运单拒绝新预约。
        assertThatThrownBy(() -> reserve(no, "rs-frz-res2", 1, "ESC-C", SHIFT_START, SHIFT_END))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("SHIPMENT_TERMINAL"));
    }

    @Test
    void reservationIdempotencyReplaysSuccessAndRejectsParamChange() {
        String no = dispatchedControlled("rs-idem", "沪A-1001");
        ServiceResult first = reserve(no, "rs-idem-res", 1, "ESC-B", SHIFT_START, SHIFT_END);
        ServiceResult replay = reserve(no, "rs-idem-res", 1, "ESC-B", SHIFT_START, SHIFT_END);
        // 同参成功：重放原响应，不产生第二条预约；重放经 JSON 存储，数值按字符串比对。
        assertThat(String.valueOf(reservationOf(replay).get("reservationId")))
                .isEqualTo(String.valueOf(reservationOf(first).get("reservationId")));
        assertThat(reservationsOf(no)).hasSize(1);
        // 改参（换精确时刻）：409。
        assertThatThrownBy(() -> reserve(no, "rs-idem-res", 1, "ESC-B",
                SHIFT_START, LocalDateTime.parse("2026-10-02T16:00:01")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("IDEMPOTENT_FINGERPRINT_MISMATCH"));
    }
}
