package com.example.starter.feed.service;

import com.example.starter.feed.domain.BatchMedication;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 休药期计算纯逻辑单测：UTC 日界、恰满当日 24:00 边界、多配方合并口径。
 */
class WithdrawalCalculatorTest {

    private BatchMedication med(String formula, int days, String lastFeeding) {
        return new BatchMedication(null, "B1", formula, 1, Instant.parse(lastFeeding), days,
                Instant.parse("2026-09-01T00:00:00Z"), false);
    }

    @Test
    void boundaryIsEndOfTheExactWithdrawalDayUtc() {
        // 末次饲喂 2026-09-01T10:00Z，休药 7 天：
        // 恰满当日 = 2026-09-08，24:00 后合法 == 2026-09-09T00:00:00Z 起合法。
        WithdrawalCalculator.Window window = WithdrawalCalculator.calculate(
                List.of(med("F1", 7, "2026-09-01T10:00:00Z")));
        assertThat(window.earliestSlaughterDate()).isEqualTo(LocalDate.of(2026, 9, 9));
        assertThat(window.eligibleInstant()).isEqualTo(Instant.parse("2026-09-09T00:00:00Z"));

        assertThat(WithdrawalCalculator.canSlaughter(window, Instant.parse("2026-09-08T23:59:59Z")))
                .isFalse();
        // 恰等于合法时刻（恰满休药天数当日 24:00）合法
        assertThat(WithdrawalCalculator.canSlaughter(window, Instant.parse("2026-09-09T00:00:00Z")))
                .isTrue();
        assertThat(WithdrawalCalculator.canSlaughter(window, Instant.parse("2026-09-09T08:00:00Z")))
                .isTrue();
    }

    @Test
    void feedingLateInUtcDayStillCountsFromThatUtcDay() {
        // 23:30 UTC 饲喂仍属当日，日界不随业务时区漂移。
        WithdrawalCalculator.Window window = WithdrawalCalculator.calculate(
                List.of(med("F1", 1, "2026-09-01T23:30:00Z")));
        assertThat(window.earliestSlaughterDate()).isEqualTo(LocalDate.of(2026, 9, 3));
    }

    @Test
    void multipleFormulasMergeByMaxDaysAndLatestFeeding() {
        WithdrawalCalculator.Window window = WithdrawalCalculator.calculate(List.of(
                med("F1", 7, "2026-09-01T10:00:00Z"),
                med("F2", 14, "2026-09-03T06:00:00Z"),
                med("F3", 5, "2026-09-02T12:00:00Z")));
        assertThat(window.maxWithdrawalDays()).isEqualTo(14);
        assertThat(window.latestFeedingTime()).isEqualTo(Instant.parse("2026-09-03T06:00:00Z"));
        // 最晚饲喂日 09-03 + 14 天当日 24:00 后 → 09-18 起合法
        assertThat(window.earliestSlaughterDate()).isEqualTo(LocalDate.of(2026, 9, 18));
    }

    @Test
    void emptyMedicationsHaveNoWindow() {
        assertThatThrownBy(() -> WithdrawalCalculator.calculate(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
