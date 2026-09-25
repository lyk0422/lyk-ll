package com.example.starter.atm.support;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * UTC 日界与第 3 自然日边界纯单元测试（无数据库）。
 */
class UtcDatesTest {

    @Test
    void dateOf_usesUtcDayBoundary() {
        // 2026-01-10 23:59:59.999Z 与次日 00:00:00Z 分属两个 UTC 日
        assertEquals("2026-01-10",
                UtcDates.dateOf(Instant.parse("2026-01-10T23:59:59.999Z").toEpochMilli()));
        assertEquals("2026-01-11",
                UtcDates.dateOf(Instant.parse("2026-01-11T00:00:00Z").toEpochMilli()));
    }

    @Test
    void plusDays_thirdNaturalDayIsExactly72HoursLaterByUtcDate() {
        // 发生 2026-01-10（任意时刻），销账截止 UTC 日 = 2026-01-13（恰第 3 个自然日）
        assertEquals("2026-01-13", UtcDates.plusDays("2026-01-10", 3));
        // 截止日终点为 2026-01-13 23:59:59.999Z，之后即第 4 日逾期
        long endOfDay3 = UtcDates.endOfDateMillis("2026-01-13");
        assertEquals("2026-01-13", UtcDates.dateOf(endOfDay3));
        assertEquals("2026-01-14", UtcDates.dateOf(endOfDay3 + 1));
    }

    @Test
    void plusDays_handlesMonthRollover() {
        assertEquals("2026-02-01", UtcDates.plusDays("2026-01-29", 3));
    }
}
