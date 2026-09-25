package com.example.starter.hazmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.example.starter.hazmat.api.ApiException;
import com.example.starter.hazmat.service.ServiceResult;

/**
 * 押运打卡时序：运单+途经点去重、乱序回传按时刻归位、逆序拒绝并告警、恰 60 分钟超时边界。
 *
 * <p>R-G3 计划通过时刻（发运 08:00）：p0 08:30、p1 09:00、p2 09:30、p3 10:00；
 * 超时阈值依次为 09:30、10:00、10:30、11:00。
 */
class CheckinTimingTest extends HazmatIntegrationTestBase {

    private static final LocalDateTime DEPARTURE = LocalDateTime.parse("2026-10-02T08:00:00");

    private String dispatched(String reqPrefix) {
        return dispatched(reqPrefix, "沪A-1001");
    }

    private String dispatched(String reqPrefix, String plate) {
        String no = shipmentNo(apply(reqPrefix + "-apply", plate, "FLAMMABLE", "R-G3", DEPARTURE));
        approve(no);
        dispatch(no, reqPrefix + "-dispatch");
        return no;
    }

    @Test
    void duplicateWaypointCheckinRejected() {
        String no = dispatched("dup");
        checkin(no, "dup-0", 0, DEPARTURE.plusMinutes(31));
        assertThatThrownBy(() -> checkin(no, "dup-0-again", 0, DEPARTURE.plusMinutes(32)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("WAYPOINT_ALREADY_CHECKED"));
    }

    @Test
    void outOfOrderAcceptedAndReorderedByTime() {
        String no = dispatched("ooo");
        // 先打 p1，再回传 p0（时刻早于 p1）→ 允许，按时刻归位。
        ServiceResult c1 = checkin(no, "ooo-1", 1, DEPARTURE.plusMinutes(62));
        assertThat(status(c1)).isEqualTo("IN_TRANSIT");
        ServiceResult c0 = checkin(no, "ooo-0", 0, DEPARTURE.plusMinutes(33));
        assertThat(status(c0)).isEqualTo("IN_TRANSIT");
        assertThat(waypoint(no, 0).get("checkedTime")).isEqualTo(DEPARTURE.plusMinutes(33).toString());
        assertThat(waypoint(no, 1).get("checkedTime")).isEqualTo(DEPARTURE.plusMinutes(62).toString());
    }

    @Test
    void reverseOrderRejectedAndAlerted() {
        String no = dispatched("rev");
        // 先打 p1 @09:05，再传 p0 @09:10（时刻不早于 p1）→ 逆序拒绝 400 并产生告警事件。
        checkin(no, "rev-1", 1, DEPARTURE.plusMinutes(65));
        assertThatThrownBy(() -> checkin(no, "rev-0-bad", 0, DEPARTURE.plusMinutes(70)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("CHECKIN_REVERSED"));
        // p0 未被写入打卡，逆序告警事件独立保留。
        assertThat(waypoint(no, 0).get("state")).isEqualTo("PENDING");
        assertThat(events(view(no)).stream().anyMatch(e -> "CHECKIN_REVERSED".equals(e.get("eventType")))).isTrue();

        // 同一途经点用合法时刻重新打卡仍可成功（拒绝不等于占用序号）。
        checkin(no, "rev-0-ok", 0, DEPARTURE.plusMinutes(64));
        assertThat(waypoint(no, 0).get("state")).isEqualTo("CHECKED");
    }

    @Test
    void exactlySixtyMinutesIsTimedOut() {
        String no = dispatched("deadline", "沪A-1001");
        // p0 计划 08:30，阈值 09:30。恰满 60 分钟（==09:30）打卡 → 判超时，记 CHECKED_LATE。
        ServiceResult exact = checkin(no, "dl-exact", 0, DEPARTURE.plusMinutes(90));
        assertThat(status(exact)).isEqualTo("IN_TRANSIT");
        assertThat(waypoint(no, 0).get("state")).isEqualTo("CHECKED_LATE");

        // 差 1 分钟（09:29）为准点。
        String no2 = dispatched("deadline2", "沪A-1002");
        checkin(no2, "dl-before", 0, DEPARTURE.plusMinutes(89));
        assertThat(waypoint(no2, 0).get("state")).isEqualTo("CHECKED");
    }
}
