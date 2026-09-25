package com.example.starter.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 速率复算引擎纯单元测试：恰等阈值正常、乱序归位、红色分级、24 小时安全窗口边界。
 */
class RateCalculatorTest {

    private static final BigDecimal WARN = new BigDecimal("5.0");
    private static final BigDecimal ALARM = new BigDecimal("100.0");
    private static final BigDecimal SAFETY = new BigDecimal("2.0");

    private final LocalDateTime t0 = LocalDateTime.of(2026, 9, 25, 0, 0);

    private RateCalculator.Reading r(int hourOffset, double mm) {
        return new RateCalculator.Reading(t0.plusHours(hourOffset), BigDecimal.valueOf(mm));
    }

    @Test
    void hourlyRateExactlyEqualsWarnThreshold_isNormal() {
        // 每小时位移恰好 5mm，小时速率恰等预警值 5.0 → 判正常
        List<RateCalculator.Reading> readings = List.of(r(0, 0), r(1, 5));
        RateCalculator.AlarmVerdict verdict = RateCalculator.classify(readings, t0.plusHours(1), WARN, ALARM);
        assertEquals(RateCalculator.AlarmVerdict.Level.NORMAL, verdict.level());
        assertEquals(0, new BigDecimal("5.000000").compareTo(verdict.hourlyRate()));
    }

    @Test
    void hourlyRateJustAboveWarnThreshold_isYellow() {
        List<RateCalculator.Reading> readings = List.of(r(0, 0), r(1, 5.01));
        RateCalculator.AlarmVerdict verdict = RateCalculator.classify(readings, t0.plusHours(1), WARN, ALARM);
        assertEquals(RateCalculator.AlarmVerdict.Level.YELLOW, verdict.level());
    }

    @Test
    void dailyRateExactlyEqualsAlarmThreshold_isNotRed() {
        // 24 小时累计恰好 100mm → 日速率恰等报警值 → 不构成红色
        List<RateCalculator.Reading> readings = new ArrayList<>();
        for (int h = 0; h <= 24; h++) {
            readings.add(r(h, (100.0 * h) / 24.0));
        }
        RateCalculator.AlarmVerdict verdict = RateCalculator.classify(readings, t0.plusHours(24), WARN, ALARM);
        assertFalse(verdict.level() == RateCalculator.AlarmVerdict.Level.RED,
                "日速率恰等报警值不得判红色");
    }

    @Test
    void dailyRateAboveAlarmThreshold_isRed() {
        // 5mm/小时，24 小时累计 120mm > 100mm/日 → 红色
        List<RateCalculator.Reading> readings = new ArrayList<>();
        for (int h = 0; h <= 24; h++) {
            readings.add(r(h, 5.0 * h));
        }
        RateCalculator.AlarmVerdict verdict = RateCalculator.classify(readings, t0.plusHours(24), WARN, ALARM);
        assertEquals(RateCalculator.AlarmVerdict.Level.RED, verdict.level());
        assertEquals(0, new BigDecimal("120.000000").compareTo(verdict.dailyRate()));
    }

    @Test
    void outOfOrderReadingsAreSortedBeforeRecompute() {
        List<RateCalculator.Reading> shuffled = new ArrayList<>(List.of(r(2, 10), r(0, 0), r(1, 5)));
        List<RateCalculator.RatePoint> curve = RateCalculator.curve(shuffled);
        assertEquals(t0, curve.get(0).time());
        assertEquals(t0.plusHours(1), curve.get(1).time());
        assertEquals(t0.plusHours(2), curve.get(2).time());
    }

    @Test
    void duplicateTimestampsDeduped() {
        List<RateCalculator.Reading> dup = List.of(r(1, 5), r(1, 9), r(0, 0));
        List<RateCalculator.RatePoint> curve = RateCalculator.curve(dup);
        assertEquals(2, curve.size());
    }

    @Test
    void safetyWindowExactly24Hours_isEligible() {
        LocalDateTime trigger = t0;
        List<RateCalculator.Reading> readings = new ArrayList<>(List.of(r(0, 0)));
        // 触发后每小时 1mm，严格低于安全线 2mm/小时
        for (int h = 1; h <= 24; h++) {
            readings.add(new RateCalculator.Reading(trigger.plusHours(h), BigDecimal.valueOf(h)));
        }
        RateCalculator.SafetyWindowResult result = RateCalculator.evaluateSafetyWindow(
                readings, trigger, trigger.plusHours(24), SAFETY);
        assertTrue(result.eligible(), "恰满24小时应允许复工申请: " + result.reason());
        assertEquals(trigger, result.safeSince());
    }

    @Test
    void safetyWindowOneMinuteShort_isNotEligible() {
        LocalDateTime trigger = t0;
        List<RateCalculator.Reading> readings = new ArrayList<>(List.of(r(0, 0)));
        for (int h = 1; h <= 23; h++) {
            readings.add(new RateCalculator.Reading(trigger.plusHours(h), BigDecimal.valueOf(h)));
        }
        readings.add(new RateCalculator.Reading(trigger.plusHours(23).plusMinutes(59),
                new BigDecimal("23.99")));
        RateCalculator.SafetyWindowResult result = RateCalculator.evaluateSafetyWindow(
                readings, trigger, trigger.plusHours(23).plusMinutes(59), SAFETY);
        assertFalse(result.eligible());
    }

    @Test
    void rateExactlyAtSafetyLineBreaksContinuousWindow() {
        // 恰等安全线（非严格低于）属于不安全，连续窗口从该点重新起算
        LocalDateTime trigger = t0;
        List<RateCalculator.Reading> readings = new ArrayList<>(List.of(r(0, 0)));
        for (int h = 1; h <= 24; h++) {
            double mm = h == 5 ? 4 + 2 : h; // 第 5 小时速率恰等 2.0
            readings.add(new RateCalculator.Reading(trigger.plusHours(h), BigDecimal.valueOf(mm)));
        }
        RateCalculator.SafetyWindowResult result = RateCalculator.evaluateSafetyWindow(
                readings, trigger, trigger.plusHours(24), SAFETY);
        assertFalse(result.eligible());
        assertEquals(trigger.plusHours(5), result.safeSince());
    }

    @Test
    void noReadingAtTriggerTime_returnsNormalWithReason() {
        RateCalculator.AlarmVerdict verdict = RateCalculator.classify(
                List.of(r(0, 0)), t0.plusHours(3), WARN, ALARM);
        assertEquals(RateCalculator.AlarmVerdict.Level.NORMAL, verdict.level());
        assertNotNull(verdict.reason());
    }
}
