package com.example.starter.damage.service.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TimeWindowsTest {

    private static final long DAY = TimeWindows.DAY_MILLIS;

    private static long utcDay(int year, int month, int day) {
        return LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    @Test
    @DisplayName("批复有效期按 UTC 日界：批复当日不计入，截止于批复日+8 的 00:00")
    void windowAnchoredToUtcDayBoundary() {
        long approvedAt = utcDay(2026, 9, 1) + 12 * 3_600_000L;
        long validUntil = TimeWindows.approvalValidUntilUtc(approvedAt);
        assertThat(validUntil).isEqualTo(utcDay(2026, 9, 9));
    }

    @Test
    @DisplayName("恰第7个自然日（批复日+7 的 23:59:59.999）完工仍有效")
    void completionOnSeventhDayIsValid() {
        long approvedAt = utcDay(2026, 9, 1) + 12 * 3_600_000L;
        long validUntil = TimeWindows.approvalValidUntilUtc(approvedAt);
        long endOfDay7 = utcDay(2026, 9, 8) + DAY - 1;
        assertThat(TimeWindows.withinApprovalWindow(endOfDay7, validUntil)).isTrue();
    }

    @Test
    @DisplayName("第8个自然日 00:00 完工即逾期，须重新估价")
    void completionOnEighthDayIsExpired() {
        long approvedAt = utcDay(2026, 9, 1) + 12 * 3_600_000L;
        long validUntil = TimeWindows.approvalValidUntilUtc(approvedAt);
        long startOfDay8 = utcDay(2026, 9, 9);
        assertThat(TimeWindows.withinApprovalWindow(startOfDay8, validUntil)).isFalse();
    }
}
