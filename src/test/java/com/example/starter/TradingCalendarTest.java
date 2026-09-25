package com.example.starter;

import com.example.starter.service.TradingCalendar;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 交易日历单元测试：补足截止时间为通知后次一交易日 15:00（UTC+8），周末顺延。
 */
class TradingCalendarTest {

    private final TradingCalendar calendar = new TradingCalendar();

    @Test
    void weekdayTrigger_deadlineIsNextDay15() {
        // 周一触发 -> 周二 15:00
        assertEquals(LocalDateTime.of(2026, 9, 22, 15, 0),
                calendar.nextTradingDayDeadline(LocalDateTime.of(2026, 9, 21, 14, 30)));
    }

    @Test
    void fridayTrigger_deadlineSkipsWeekend() {
        // 2026-09-25 周五触发 -> 2026-09-28 周一 15:00
        assertEquals(LocalDateTime.of(2026, 9, 28, 15, 0),
                calendar.nextTradingDayDeadline(LocalDateTime.of(2026, 9, 25, 10, 0)));
    }

    @Test
    void weekendTrigger_deadlineIsMonday() {
        // 周六触发 -> 周一 15:00
        assertEquals(LocalDateTime.of(2026, 9, 28, 15, 0),
                calendar.nextTradingDayDeadline(LocalDateTime.of(2026, 9, 26, 9, 0)));
    }
}
