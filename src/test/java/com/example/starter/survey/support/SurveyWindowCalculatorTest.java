package com.example.starter.survey.support;

import com.example.starter.survey.domain.Ship;
import com.example.starter.survey.domain.SurveyType;
import com.example.starter.survey.support.SurveyWindowCalculator.SurveyWindow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 窗口纯计算：首末日恰达、闰年周年日、中间/特别周期、30 日期限与展期月度加法。 */
class SurveyWindowCalculatorTest {

    private final Ship ship = new Ship(1L, "IMO-T", "测试船",
            LocalDate.of(2020, 6, 15), LocalDate.of(2020, 3, 1));

    @Test
    void annualWindowIsThreeMonthsEachSideInclusive() {
        SurveyWindow w = SurveyWindowCalculator.windowAround(
                SurveyType.ANNUAL, LocalDate.of(2026, 6, 15));
        assertEquals(LocalDate.of(2026, 3, 15), w.start());
        assertEquals(LocalDate.of(2026, 9, 15), w.end());

        // 恰在窗口首日、末日、周年日均有效。
        assertTrue(w.contains(LocalDate.of(2026, 3, 15)));
        assertTrue(w.contains(LocalDate.of(2026, 9, 15)));
        assertTrue(w.contains(LocalDate.of(2026, 6, 15)));
        // 前一日、后一日无效。
        assertFalse(w.contains(LocalDate.of(2026, 3, 14)));
        assertFalse(w.contains(LocalDate.of(2026, 9, 16)));
    }

    @Test
    void annualWindowHitsByContaining() {
        LocalDate anchor2026 = LocalDate.of(2026, 6, 15);
        assertTrue(SurveyWindowCalculator
                .windowContaining(ship, SurveyType.ANNUAL, LocalDate.of(2026, 3, 15))
                .isPresent());
        assertTrue(SurveyWindowCalculator
                .windowContaining(ship, SurveyType.ANNUAL, LocalDate.of(2026, 9, 15))
                .isPresent());
        assertTrue(SurveyWindowCalculator
                .windowContaining(ship, SurveyType.ANNUAL, LocalDate.of(2026, 6, 15))
                .get().anchor().equals(anchor2026));
        assertFalse(SurveyWindowCalculator
                .windowContaining(ship, SurveyType.ANNUAL, LocalDate.of(2026, 3, 14))
                .isPresent());
        assertFalse(SurveyWindowCalculator
                .windowContaining(ship, SurveyType.ANNUAL, LocalDate.of(2026, 9, 16))
                .isPresent());
    }

    @Test
    void leapDayAnniversaryFallsBackToFeb28() {
        Ship leapShip = new Ship(2L, "IMO-L", "闰年船",
                LocalDate.of(2020, 2, 29), LocalDate.of(2020, 2, 29));
        assertEquals(LocalDate.of(2026, 2, 28),
                SurveyWindowCalculator.annualAnchor(leapShip, 2026));
        assertEquals(LocalDate.of(2024, 2, 29),
                SurveyWindowCalculator.annualAnchor(leapShip, 2024));
    }

    @Test
    void intermediateAndSpecialCycles() {
        // 特别检验：交付起每 5 年。2025-03-01 窗口覆盖 2025-03-15。
        assertTrue(SurveyWindowCalculator
                .windowContaining(ship, SurveyType.SPECIAL, LocalDate.of(2025, 3, 15))
                .isPresent());
        assertFalse(SurveyWindowCalculator
                .windowContaining(ship, SurveyType.SPECIAL, LocalDate.of(2026, 6, 15))
                .isPresent());
        // 中间检验：5 年周期内第 2、3 周年日，2022-03-01 窗口覆盖 2022-04-01。
        assertTrue(SurveyWindowCalculator
                .windowContaining(ship, SurveyType.INTERMEDIATE, LocalDate.of(2022, 4, 1))
                .isPresent());
        // 2021 年无中间检验窗口。
        assertFalse(SurveyWindowCalculator
                .windowContaining(ship, SurveyType.INTERMEDIATE, LocalDate.of(2021, 6, 1))
                .isPresent());
    }

    @Test
    void minorDeadlineIsExactlyThirtyDaysInclusive() {
        LocalDate found = LocalDate.of(2026, 6, 15);
        LocalDate deadline = SurveyWindowCalculator.minorDeadline(found);
        assertEquals(LocalDate.of(2026, 7, 15), deadline);
        // 恰第 30 日由服务层按 !submit.isAfter(deadline) 判定受理。
        assertFalse(deadline.isBefore(LocalDate.of(2026, 7, 15)));
        assertTrue(LocalDate.of(2026, 7, 16).isAfter(deadline));
    }

    @Test
    void extensionUsesMonthArithmeticExactlyThreeMonths() {
        // 恰满 3 个月合法；月末按月度加法（12/31 + 3 月 = 次年 3/31）。
        assertEquals(LocalDate.of(2026, 12, 15),
                SurveyWindowCalculator.extend(LocalDate.of(2026, 9, 15), 3));
        assertEquals(LocalDate.of(2027, 3, 31),
                SurveyWindowCalculator.extend(LocalDate.of(2026, 12, 31), 3));
        assertEquals(LocalDate.of(2027, 2, 28),
                SurveyWindowCalculator.extend(LocalDate.of(2026, 11, 30), 3));
    }
}
