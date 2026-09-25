package com.example.starter.legality.service;

import com.example.starter.legality.domain.DutyRecord;
import com.example.starter.legality.domain.ViolationReasons;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LegalityRules} 纯单元测试：所有“恰满合法、超出违规”的时间边界。
 */
class LegalityRulesTest {

    private static OffsetDateTime utc(String text) {
        return OffsetDateTime.parse(text).withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static DutyRecord duty(String crewId, String report, String end) {
        return new DutyRecord("r-" + report, null, crewId, "CA100",
                utc(report), utc(end), 0, true, "",
                utc(report).minusHours(1));
    }

    @Test
    void fdpLimitFollowsReportTimeTiers() {
        assertThat(LegalityRules.fdpLimitMinutes(utc("2026-03-10T06:00:00Z"))).isEqualTo(13 * 60);
        assertThat(LegalityRules.fdpLimitMinutes(utc("2026-03-10T12:59:00Z"))).isEqualTo(13 * 60);
        assertThat(LegalityRules.fdpLimitMinutes(utc("2026-03-10T13:00:00Z"))).isEqualTo(12 * 60);
        assertThat(LegalityRules.fdpLimitMinutes(utc("2026-03-10T21:59:00Z"))).isEqualTo(12 * 60);
        assertThat(LegalityRules.fdpLimitMinutes(utc("2026-03-10T22:00:00Z"))).isEqualTo(10 * 60);
        assertThat(LegalityRules.fdpLimitMinutes(utc("2026-03-10T05:59:00Z"))).isEqualTo(10 * 60);
        assertThat(LegalityRules.fdpLimitMinutes(utc("2026-03-10T00:00:00Z"))).isEqualTo(10 * 60);
    }

    @Test
    void morningTierExactly13HoursIsLegal() {
        assertThat(LegalityRules.evaluate(
                utc("2026-03-10T06:00:00Z"), utc("2026-03-10T19:00:00Z"),
                0, List.of(), 0)).isEmpty();
    }

    @Test
    void morningTierOneMinuteOver13HoursViolates() {
        assertThat(LegalityRules.evaluate(
                utc("2026-03-10T06:00:00Z"), utc("2026-03-10T19:01:00Z"),
                0, List.of(), 0)).containsExactly(ViolationReasons.M_FDP_LIMIT);
    }

    @Test
    void eveningTierExactly12HoursIsLegal() {
        assertThat(LegalityRules.evaluate(
                utc("2026-03-10T13:00:00Z"), utc("2026-03-11T01:00:00Z"),
                0, List.of(), 0)).isEmpty();
    }

    @Test
    void eveningTierOneMinuteOver12HoursViolates() {
        assertThat(LegalityRules.evaluate(
                utc("2026-03-10T13:00:00Z"), utc("2026-03-11T01:01:00Z"),
                0, List.of(), 0)).containsExactly(ViolationReasons.M_FDP_LIMIT);
    }

    @Test
    void nightTierExactly10HoursIsLegal() {
        assertThat(LegalityRules.evaluate(
                utc("2026-03-10T22:00:00Z"), utc("2026-03-11T08:00:00Z"),
                0, List.of(), 0)).isEmpty();
    }

    @Test
    void nightTierOneMinuteOver10HoursViolates() {
        assertThat(LegalityRules.evaluate(
                utc("2026-03-10T22:00:00Z"), utc("2026-03-11T08:01:00Z"),
                0, List.of(), 0)).containsExactly(ViolationReasons.M_FDP_LIMIT);
    }

    @Test
    void dailyDelayExactly120MinutesIsLegal() {
        // 06:00 报到、计划 4 小时执勤，叠加当日既有 60 分钟与本条 60 分钟延长，恰满 120 分钟。
        assertThat(LegalityRules.evaluate(
                utc("2026-03-10T06:00:00Z"), utc("2026-03-10T10:00:00Z"),
                60, List.of(), 60)).isEmpty();
    }

    @Test
    void dailyDelayOneMinuteOver120Violates() {
        assertThat(LegalityRules.evaluate(
                utc("2026-03-10T06:00:00Z"), utc("2026-03-10T10:00:00Z"),
                61, List.of(), 60)).containsExactly(ViolationReasons.M_DAILY_DELAY_LIMIT);
    }

    @Test
    void rollingSevenDaysExactly60HoursIsLegal() {
        // D-6..D-1 每日 06:00-15:00 执勤 9 小时（间隔休息 15 小时），D 日 06:00-12:00 执勤 6 小时，合计恰 60 小时。
        List<DutyRecord> history = new ArrayList<>();
        for (int d = 6; d >= 1; d--) {
            String day = String.format("2026-03-%02d", 10 - d + 6);
            history.add(duty("C1", day + "T06:00:00Z", day + "T15:00:00Z"));
        }
        history.sort((a, b) -> a.reportTime().compareTo(b.reportTime()));
        assertThat(LegalityRules.evaluate(
                utc("2026-03-16T06:00:00Z"), utc("2026-03-16T12:00:00Z"),
                0, history, 0)).isEmpty();
    }

    @Test
    void rollingSevenDaysOneMinuteOver60HoursViolates() {
        List<DutyRecord> history = new ArrayList<>();
        for (int d = 6; d >= 1; d--) {
            String day = String.format("2026-03-%02d", 10 - d + 6);
            history.add(duty("C1", day + "T06:00:00Z", day + "T15:00:00Z"));
        }
        history.sort((a, b) -> a.reportTime().compareTo(b.reportTime()));
        assertThat(LegalityRules.evaluate(
                utc("2026-03-16T06:00:00Z"), utc("2026-03-16T12:01:00Z"),
                0, history, 0)).containsExactly(ViolationReasons.M_ROLLING_60H);
    }

    @Test
    void dutyOlderThanSevenDaysDoesNotCountTowardsRollingWindow() {
        // D-7 的 9 小时执勤落在窗口外，窗口内仅 D 日 6 小时，合法。
        DutyRecord old = duty("C1", "2026-03-09T06:00:00Z", "2026-03-09T15:00:00Z");
        assertThat(LegalityRules.evaluate(
                utc("2026-03-16T06:00:00Z"), utc("2026-03-16T12:00:00Z"),
                0, List.of(old), 0)).isEmpty();
    }

    @Test
    void restExactly10HoursIsLegal() {
        DutyRecord previous = duty("C1", "2026-03-10T06:00:00Z", "2026-03-10T16:00:00Z");
        assertThat(LegalityRules.evaluate(
                utc("2026-03-11T02:00:00Z"), utc("2026-03-11T03:00:00Z"),
                0, List.of(previous), 0)).isEmpty();
    }

    @Test
    void restOneMinuteShortViolates() {
        DutyRecord previous = duty("C1", "2026-03-10T06:00:00Z", "2026-03-10T16:00:00Z");
        assertThat(LegalityRules.evaluate(
                utc("2026-03-11T01:59:00Z"), utc("2026-03-11T03:00:00Z"),
                0, List.of(previous), 0)).containsExactly(ViolationReasons.M_REST_LIMIT);
    }

    @Test
    void delayExtendsActualEndForRestCalculation() {
        // 前一执勤计划 16:00 结束但延误 60 分钟实际 17:00 结束，次日 03:00 报到仅休息 10 小时（恰满合法）。
        DutyRecord previous = new DutyRecord("r-old", null, "C1", "CA100",
                utc("2026-03-10T06:00:00Z"), utc("2026-03-10T16:00:00Z"),
                60, true, "", utc("2026-03-10T05:00:00Z"));
        assertThat(LegalityRules.evaluate(
                utc("2026-03-11T03:00:00Z"), utc("2026-03-11T04:00:00Z"),
                0, List.of(previous), 0)).isEmpty();
        assertThat(LegalityRules.evaluate(
                utc("2026-03-11T02:59:00Z"), utc("2026-03-11T04:00:00Z"),
                0, List.of(previous), 0)).containsExactly(ViolationReasons.M_REST_LIMIT);
    }
}
