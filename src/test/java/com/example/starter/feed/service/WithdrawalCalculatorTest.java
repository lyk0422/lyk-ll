package com.example.starter.feed.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.AbstractMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 休药期 UTC 日界口径的纯单元测试：末次饲喂日 + N 天为届满日，届满日次日 00:00 UTC 起可出栏。
 */
class WithdrawalCalculatorTest {

    @Test
    void utcDateUsesUtcDayBoundary() {
        // 北京时间 2026-09-02 凌晨，但 UTC 仍为 2026-09-01：必须按 UTC 日界。
        Instant instant = Instant.parse("2026-09-01T17:30:00Z");
        assertThat(WithdrawalCalculator.utcDate(instant)).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @Test
    void withdrawalUntilDateAddsDaysOnUtcCalendar() {
        Instant feed = Instant.parse("2026-09-01T10:00:00Z");
        // 8 天休药：届满日 = 2026-09-09。
        assertThat(WithdrawalCalculator.withdrawalUntilDate(feed, 8))
                .isEqualTo(LocalDate.of(2026, 9, 9));
    }

    @Test
    void earliestSlaughterIsMidnightAfterUntilDate() {
        // 恰满休药天数当日 24:00 = 届满日次日 00:00 UTC。
        Instant earliest = WithdrawalCalculator.earliestSlaughter(LocalDate.of(2026, 9, 9));
        assertThat(earliest).isEqualTo(Instant.parse("2026-09-10T00:00:00Z"));
    }

    @Test
    void exactBoundaryIsAllowedOneSecondBeforeIsNot() {
        Instant feed = Instant.parse("2026-09-01T10:00:00Z");
        LocalDate until = WithdrawalCalculator.withdrawalUntilDate(feed, 8);
        Instant earliest = WithdrawalCalculator.earliestSlaughter(until);

        assertThat(Instant.parse("2026-09-09T23:59:59Z").isBefore(earliest)).isTrue();
        assertThat(Instant.parse("2026-09-10T00:00:00Z").isBefore(earliest)).isFalse();
        assertThat(Instant.parse("2026-09-10T00:00:00Z")).isEqualTo(earliest);
    }

    @Test
    void mergeTakesLatestFeedAndMaxDaysAcrossFormulas() {
        // 同一批次两个含药配方：药物 7 天但饲喂早；药物 14 天但饲喂晚。
        Map.Entry<Instant, Integer> a = new AbstractMap.SimpleEntry<>(
                Instant.parse("2026-09-01T08:00:00Z"), 7);
        Map.Entry<Instant, Integer> b = new AbstractMap.SimpleEntry<>(
                Instant.parse("2026-09-03T20:00:00Z"), 14);

        WithdrawalCalculator.MergedWithdrawal merged = WithdrawalCalculator.merge(a, b);

        assertThat(merged.latestFeedAtUtc()).isEqualTo(Instant.parse("2026-09-03T20:00:00Z"));
        assertThat(merged.maxWithdrawalDays()).isEqualTo(14);
        assertThat(merged.withdrawalUntilDate()).isEqualTo(LocalDate.of(2026, 9, 17));
        assertThat(merged.earliestSlaughterUtc()).isEqualTo(Instant.parse("2026-09-18T00:00:00Z"));
    }

    @Test
    void mergeWithNoContributionsReturnsNull() {
        assertThat(WithdrawalCalculator.merge()).isNull();
    }

    @Test
    void negativeDaysRejected() {
        assertThatThrownBy(() ->
                WithdrawalCalculator.withdrawalUntilDate(Instant.parse("2026-09-01T00:00:00Z"), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
