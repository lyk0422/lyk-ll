package com.example.starter.lc.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * 合成银行工作日历单元测试：5 个银行工作日边界与跨周末计算。
 */
class BankCalendarTest {

    private static Instant at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).toInstant(ZoneOffset.UTC);
    }

    @Test
    void deadlineSkipsWeekendWhenReceivedOnFriday() {
        // 2026-09-18 为周五：次日起 5 个工作日为 09-21~09-25，截止 09-25 17:00 UTC
        Instant deadline = BankCalendar.refusalDeadline(at(LocalDate.of(2026, 9, 18), 10, 0));
        assertThat(deadline).isEqualTo(LocalDate.of(2026, 9, 25).atTime(17, 0).toInstant(ZoneOffset.UTC));
    }

    @Test
    void deadlineWhenReceivedOnMonday() {
        // 2026-09-21 周一：次日起 5 个工作日为 09-22~09-28（含周末跳过）
        Instant deadline = BankCalendar.refusalDeadline(at(LocalDate.of(2026, 9, 21), 9, 30));
        assertThat(deadline).isEqualTo(LocalDate.of(2026, 9, 28).atTime(17, 0).toInstant(ZoneOffset.UTC));
    }

    @Test
    void refusalExactlyBefore1700IsTimely() {
        Instant deadline = LocalDate.of(2026, 9, 25).atTime(17, 0).toInstant(ZoneOffset.UTC);
        assertThat(BankCalendar.isRefusalTimely(
                LocalDate.of(2026, 9, 25).atTime(16, 59, 59).toInstant(ZoneOffset.UTC), deadline))
                .isTrue();
    }

    @Test
    void refusalAt1700SharpIsTooLate() {
        Instant deadline = LocalDate.of(2026, 9, 25).atTime(17, 0).toInstant(ZoneOffset.UTC);
        assertThat(BankCalendar.isRefusalTimely(deadline, deadline)).isFalse();
        assertThat(BankCalendar.isRefusalTimely(
                LocalDate.of(2026, 9, 25).atTime(17, 0, 1).toInstant(ZoneOffset.UTC), deadline))
                .isFalse();
    }
}
