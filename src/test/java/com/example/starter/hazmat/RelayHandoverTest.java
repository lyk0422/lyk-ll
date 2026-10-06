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
 * 夜间接力押运双人打卡：接力点设置、交接班双方编号与时刻、单人打卡不生效、
 * 乱序回传按时刻归位、逆序拒绝并告警、运单+途经点序号去重、requestId 幂等。
 *
 * <p>场景：R-G3 夜间禁行 2026-10-03 00:00~05:00；运单 2026-10-02 22:30 发运，
 * 计划通过 p0 23:00、p1 23:30、p2 00:00、p3 00:30 → 运输窗口跨越禁行起点，须设置安全接力点。
 */
class RelayHandoverTest extends HazmatIntegrationTestBase {

    private static final LocalDateTime DEPARTURE = LocalDateTime.parse("2026-10-02T22:30:00");
    private static final LocalDateTime CURFEW_START = LocalDateTime.parse("2026-10-03T00:00:00");

    @BeforeEach
    void fixClock() {
        // 固定业务时钟于禁行起点前，避免真实时钟触发懒冻结。
        clock.setTime(LocalDateTime.parse("2026-10-02T22:30:00"));
    }

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
    void relayRequiredAndDoubleCheckinCompletesHandover() {
        String no = dispatched("rh-main", "沪A-1001");
        // 运输窗口跨越禁行起点 → 须设置安全接力点。
        assertThat(view(no).get("relayRequired")).isEqualTo(true);
        assertThat(view(no).get("curfewStart")).isEqualTo(CURFEW_START.toString());

        ServiceResult set = setRelayPoint(no, "rh-main-rp", 1);
        assertThat(set.httpStatus()).isEqualTo(200);
        assertThat(segmentOf(no, 1).get("status")).isEqualTo("IN_PROGRESS");

        // 单人打卡不生效：仅交班一侧，换班未完成。
        ServiceResult out = relayCheckin(no, "rh-main-out", "ESC-01", "OUT",
                LocalDateTime.parse("2026-10-02T23:20:00"));
        assertThat(out.body().get("handoverCompleted")).isEqualTo(false);
        assertThat(segmentOf(no, 1).get("status")).isEqualTo("IN_PROGRESS");

        // 接班打卡后换班完成：记录含双方编号与时刻。
        ServiceResult in = relayCheckin(no, "rh-main-in", "ESC-02", "IN",
                LocalDateTime.parse("2026-10-02T23:25:00"));
        assertThat(in.body().get("handoverCompleted")).isEqualTo(true);
        Map<String, Object> segment = segmentOf(no, 1);
        assertThat(segment.get("status")).isEqualTo("COMPLETED");
        assertThat(segment.get("outEscortId")).isEqualTo("ESC-01");
        assertThat(segment.get("outTime")).isEqualTo("2026-10-02T23:20");
        assertThat(segment.get("inEscortId")).isEqualTo("ESC-02");
        assertThat(segment.get("inTime")).isEqualTo("2026-10-02T23:25");
        assertThat(segment.get("completedAt")).isEqualTo("2026-10-02T23:25");
        assertThat(events(view(no)).stream()
                .anyMatch(e -> "RELAY_HANDOVER".equals(e.get("eventType")))).isTrue();
    }

    @Test
    void sameEscortBothRolesRejectedAsSinglePerson() {
        String no = dispatched("rh-same", "沪A-1001");
        setRelayPoint(no, "rh-same-rp", 1);
        relayCheckin(no, "rh-same-out", "ESC-01", "OUT", LocalDateTime.parse("2026-10-02T23:20:00"));
        // 同一押运员打卡接班 → 单人打卡不生效，400。
        assertThatThrownBy(() -> relayCheckin(no, "rh-same-in", "ESC-01", "IN",
                LocalDateTime.parse("2026-10-02T23:25:00")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("RELAY_SAME_ESCORT"));
        assertThat(segmentOf(no, 1).get("status")).isEqualTo("IN_PROGRESS");
        // 不同押运员接班后正常完成。
        relayCheckin(no, "rh-same-in2", "ESC-02", "IN", LocalDateTime.parse("2026-10-02T23:26:00"));
        assertThat(segmentOf(no, 1).get("status")).isEqualTo("COMPLETED");
    }

    @Test
    void outOfOrderSubmissionOrderedByTime() {
        String no = dispatched("rh-ooo", "沪A-1002");
        setRelayPoint(no, "rh-ooo-rp", 1);
        // 接班打卡先回传（时刻 23:25），交班后回传（时刻 23:20）→ 按时刻归位，换班完成。
        ServiceResult in = relayCheckin(no, "rh-ooo-in", "ESC-02", "IN",
                LocalDateTime.parse("2026-10-02T23:25:00"));
        assertThat(in.body().get("handoverCompleted")).isEqualTo(false);
        ServiceResult out = relayCheckin(no, "rh-ooo-out", "ESC-01", "OUT",
                LocalDateTime.parse("2026-10-02T23:20:00"));
        assertThat(out.body().get("handoverCompleted")).isEqualTo(true);
        Map<String, Object> segment = segmentOf(no, 1);
        assertThat(segment.get("outTime")).isEqualTo("2026-10-02T23:20");
        assertThat(segment.get("inTime")).isEqualTo("2026-10-02T23:25");
    }

    @Test
    void reverseCheckinRejectedAndAlerted() {
        String no = dispatched("rh-rev", "沪A-1001");
        setRelayPoint(no, "rh-rev-rp", 1);
        relayCheckin(no, "rh-rev-out", "ESC-01", "OUT", LocalDateTime.parse("2026-10-02T23:25:00"));
        // 接班时刻早于交班时刻 → 逆序拒绝并告警。
        assertThatThrownBy(() -> relayCheckin(no, "rh-rev-in-bad", "ESC-02", "IN",
                LocalDateTime.parse("2026-10-02T23:20:00")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("RELAY_CHECKIN_REVERSED"));
        // 恰同时刻也不满足"严格晚于" → 逆序拒绝。
        assertThatThrownBy(() -> relayCheckin(no, "rh-rev-in-eq", "ESC-02", "IN",
                LocalDateTime.parse("2026-10-02T23:25:00")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("RELAY_CHECKIN_REVERSED"));
        // 拒绝不产生打卡记录，告警事件独立保留。
        assertThat(segmentOf(no, 1).get("status")).isEqualTo("IN_PROGRESS");
        assertThat(events(view(no)).stream()
                .filter(e -> "RELAY_CHECKIN_REVERSED".equals(e.get("eventType"))).count()).isEqualTo(2);
        // 合法时刻重新打卡仍可完成换班。
        relayCheckin(no, "rh-rev-in-ok", "ESC-02", "IN", LocalDateTime.parse("2026-10-02T23:26:00"));
        assertThat(segmentOf(no, 1).get("status")).isEqualTo("COMPLETED");
    }

    @Test
    void duplicateRoleAndRelayPointDedup() {
        String no = dispatched("rh-dup", "沪A-1002");
        setRelayPoint(no, "rh-dup-rp", 1);
        // 运单+途经点序号去重：同一途经点不可重复设置接力段。
        assertThatThrownBy(() -> setRelayPoint(no, "rh-dup-rp2", 1))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("RELAY_POINT_DUPLICATE"));
        // 同一运单同时刻只允许一个进行中接力段。
        assertThatThrownBy(() -> setRelayPoint(no, "rh-dup-rp0", 0))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("RELAY_SEGMENT_ACTIVE"));
        // 同一角色重复打卡（不同 requestId）→ 去重拒绝。
        relayCheckin(no, "rh-dup-out", "ESC-01", "OUT", LocalDateTime.parse("2026-10-02T23:20:00"));
        assertThatThrownBy(() -> relayCheckin(no, "rh-dup-out2", "ESC-09", "OUT",
                LocalDateTime.parse("2026-10-02T23:21:00")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("RELAY_CHECKIN_DUPLICATE"));
        // 换班完成后释放占用：可设置新接力段。
        relayCheckin(no, "rh-dup-in", "ESC-02", "IN", LocalDateTime.parse("2026-10-02T23:25:00"));
        assertThat(setRelayPoint(no, "rh-dup-rp0b", 0).httpStatus()).isEqualTo(200);
    }

    @Test
    void unsafeRelayPointRejected() {
        String no = dispatched("rh-unsafe", "沪B-2001");
        // p2 计划通过 00:00 恰为禁行起点，不早于起点 → 不能作为安全接力点。
        assertThatThrownBy(() -> setRelayPoint(no, "rh-unsafe-rp", 2))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("RELAY_POINT_UNSAFE"));
        // p1 计划通过 23:30 早于禁行起点 → 合法。
        assertThat(setRelayPoint(no, "rh-unsafe-rp1", 1).httpStatus()).isEqualTo(200);
    }

    @Test
    void relayEndpointsAreIdempotent() {
        String no = dispatched("rh-idem", "沪A-1001");
        // 接力点设置：同键同参返回首次结果；同键异参 409。
        ServiceResult first = setRelayPoint(no, "rh-idem-rp", 1);
        ServiceResult replay = setRelayPoint(no, "rh-idem-rp", 1);
        // 重放结果经 JSON 存储，数值类型可能收窄，按字符串比对业务值。
        assertThat(String.valueOf(replay.body().get("relaySegmentId")))
                .isEqualTo(String.valueOf(first.body().get("relaySegmentId")));
        assertThatThrownBy(() -> setRelayPoint(no, "rh-idem-rp", 0))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("IDEMPOTENT_FINGERPRINT_MISMATCH"));
        // 接力打卡：同键同参返回首次结果；同键异参 409。
        relayCheckin(no, "rh-idem-out", "ESC-01", "OUT", LocalDateTime.parse("2026-10-02T23:20:00"));
        ServiceResult inFirst = relayCheckin(no, "rh-idem-in", "ESC-02", "IN",
                LocalDateTime.parse("2026-10-02T23:25:00"));
        ServiceResult inReplay = relayCheckin(no, "rh-idem-in", "ESC-02", "IN",
                LocalDateTime.parse("2026-10-02T23:25:00"));
        assertThat(inReplay.body().get("handoverCompleted")).isEqualTo(true);
        assertThat(inReplay.body().get("handoverCompleted"))
                .isEqualTo(inFirst.body().get("handoverCompleted"));
        assertThatThrownBy(() -> relayCheckin(no, "rh-idem-in", "ESC-03", "IN",
                LocalDateTime.parse("2026-10-02T23:25:00")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("IDEMPOTENT_FINGERPRINT_MISMATCH"));
        // 幂等重放不产生重复打卡/接力段。
        assertThat(relaySegments(view(no))).hasSize(1);
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> checkins =
                (java.util.List<Map<String, Object>>) segmentOf(no, 1).get("checkins");
        assertThat(checkins).hasSize(2);
    }
}
