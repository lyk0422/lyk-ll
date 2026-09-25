package com.example.starter.time;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 补正时限工作日计算测试：恰第 5 个工作日为截止日（含当日）。
 */
class WorkdayCalculatorTest {

    @Test
    void fiveWorkdaysFromMondayIsNextMonday() {
        // 2026-09-07 为周一；5 个工作日后恰为 2026-09-14（周一）
        LocalDate monday = LocalDate.of(2026, 9, 7);
        LocalDate deadline = WorkdayCalculator.plusWorkdays(monday, 5);
        assertEquals(LocalDate.of(2026, 9, 14), deadline);
    }

    @Test
    void fiveWorkdaysSkipsWeekend() {
        // 2026-09-11 周五；加 5 个工作日跳过两个周末日，恰为 2026-09-18（周五）
        LocalDate friday = LocalDate.of(2026, 9, 11);
        assertEquals(LocalDate.of(2026, 9, 18), WorkdayCalculator.plusWorkdays(friday, 5));
    }

    @Test
    void deadlineDayItselfIsWorkdayAndAccepted() {
        LocalDate deadline = WorkdayCalculator.plusWorkdays(LocalDate.of(2026, 9, 7), 5);
        assertTrue(WorkdayCalculator.isWorkday(deadline), "恰第 5 个工作日当日提交仍应受理");
    }

    @Test
    void weekendIsNotWorkday() {
        assertFalse(WorkdayCalculator.isWorkday(LocalDate.of(2026, 9, 12)));
        assertFalse(WorkdayCalculator.isWorkday(LocalDate.of(2026, 9, 13)));
        assertTrue(WorkdayCalculator.isWorkday(LocalDate.of(2026, 9, 14)));
    }
}
