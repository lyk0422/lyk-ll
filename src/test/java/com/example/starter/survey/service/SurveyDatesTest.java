package com.example.starter.survey.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UTC 窗口与期限计算单元测试：窗口首末日恰达、第 30 日、展期 3 个月边界。
 */
class SurveyDatesTest {

    private final LocalDate base = LocalDate.of(2000, 6, 15);

    @Test
    void windowIsThreeMonthsEachSideInclusive() {
        LocalDate anniv = LocalDate.of(2026, 6, 15);
        assertEquals(LocalDate.of(2026, 3, 15), SurveyDates.windowStart(anniv));
        assertEquals(LocalDate.of(2026, 9, 15), SurveyDates.windowEnd(anniv));
        // 恰首日、恰末日均有效。
        assertTrue(SurveyDates.withinWindow(LocalDate.of(2026, 3, 15),
                SurveyDates.windowStart(anniv), SurveyDates.windowEnd(anniv)));
        assertTrue(SurveyDates.withinWindow(LocalDate.of(2026, 9, 15),
                SurveyDates.windowStart(anniv), SurveyDates.windowEnd(anniv)));
        // 前一天、后一天无效。
        assertFalse(SurveyDates.withinWindow(LocalDate.of(2026, 3, 14),
                SurveyDates.windowStart(anniv), SurveyDates.windowEnd(anniv)));
        assertFalse(SurveyDates.withinWindow(LocalDate.of(2026, 9, 16),
                SurveyDates.windowStart(anniv), SurveyDates.windowEnd(anniv)));
    }

    @Test
    void cycleYearResolvesAcrossYearBoundary() {
        // 周年日 11-30：窗口 08-30 至次年 02-28（2026 平年）。
        LocalDate nov = LocalDate.of(2000, 11, 30);
        assertEquals(2026, SurveyDates.cycleYear(nov, LocalDate.of(2026, 8, 30)));
        assertEquals(2026, SurveyDates.cycleYear(nov, LocalDate.of(2026, 12, 1)));
        assertEquals(2026, SurveyDates.cycleYear(nov, LocalDate.of(2027, 2, 28)));
        assertEquals(2027, SurveyDates.cycleYear(nov, LocalDate.of(2027, 3, 1)));
    }

    @Test
    void recheckDueIsExactlyThirtyDays() {
        LocalDate found = LocalDate.of(2026, 6, 15);
        assertEquals(LocalDate.of(2026, 7, 15), SurveyDates.recheckDue(found));
        assertTrue(!LocalDate.of(2026, 7, 15)
                .isAfter(SurveyDates.recheckDue(found)));
        assertTrue(LocalDate.of(2026, 7, 16)
                .isAfter(SurveyDates.recheckDue(found)));
    }

    @Test
    void annualCertValidUntilCoversNextCycleWindow() {
        // 2026 周期完成检验 → 覆盖至 2027 周期窗口末日 2027-09-15。
        assertEquals(LocalDate.of(2027, 9, 15),
                SurveyDates.annualCertValidUntil(LocalDate.of(2026, 6, 15), 0));
        // 恰 3 个月展期后为 2027-12-15。
        assertEquals(LocalDate.of(2027, 12, 15),
                SurveyDates.annualCertValidUntil(LocalDate.of(2026, 6, 15), 3));
    }

    @Test
    void anniversaryLeapDayFallsBackTo28() {
        LocalDate leap = LocalDate.of(2000, 2, 29);
        assertEquals(LocalDate.of(2025, 2, 28),
                SurveyDates.anniversaryInYear(leap, 2025));
        assertEquals(LocalDate.of(2028, 2, 29),
                SurveyDates.anniversaryInYear(leap, 2028));
    }
}
