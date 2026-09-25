package com.example.starter.payment.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * UTC 16:00 日切归属规则单元测试：早于 16:00 归当日，达到/晚于 16:00（含整点）归次日。
 */
class CutoffsTest {

    private final LocalDate day = LocalDate.of(2026, 9, 24);

    @Test
    void beforeCutoffBelongsToCurrentDay() {
        Instant at = day.atTime(15, 59, 59).toInstant(java.time.ZoneOffset.UTC);
        assertThat(Cutoffs.batchDate(at)).isEqualTo(day);
    }

    @Test
    void exactlyAtCutoffBelongsToNextDay() {
        Instant at = day.atTime(16, 0, 0).toInstant(java.time.ZoneOffset.UTC);
        assertThat(Cutoffs.batchDate(at)).isEqualTo(day.plusDays(1));
    }

    @Test
    void afterCutoffBelongsToNextDay() {
        Instant at = day.atTime(16, 0, 1).toInstant(java.time.ZoneOffset.UTC);
        assertThat(Cutoffs.batchDate(at)).isEqualTo(day.plusDays(1));
    }

    @Test
    void startOfDayBelongsToCurrentDay() {
        Instant at = day.atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
        assertThat(Cutoffs.batchDate(at)).isEqualTo(day);
    }

    @Test
    void lateEveningBelongsToNextDay() {
        Instant at = day.atTime(23, 59, 59).toInstant(java.time.ZoneOffset.UTC);
        assertThat(Cutoffs.batchDate(at)).isEqualTo(day.plusDays(1));
    }
}
