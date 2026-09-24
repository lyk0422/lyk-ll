package com.example.starter.payment;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日切归属规则：UTC 16:00 前归当日，16:00:00 整点及之后归次日。
 */
class CutoffClockTest {

    @Test
    void beforeCutoffBelongsToSameDay() {
        assertThat(CutoffClock.batchDateOf(Instant.parse("2026-09-24T15:59:59Z")))
                .isEqualTo(LocalDate.of(2026, 9, 24));
        assertThat(CutoffClock.batchDateOf(Instant.parse("2026-09-24T00:00:00Z")))
                .isEqualTo(LocalDate.of(2026, 9, 24));
    }

    @Test
    void exactlyAtCutoffBelongsToNextDay() {
        assertThat(CutoffClock.batchDateOf(Instant.parse("2026-09-24T16:00:00Z")))
                .isEqualTo(LocalDate.of(2026, 9, 25));
    }

    @Test
    void afterCutoffBelongsToNextDay() {
        assertThat(CutoffClock.batchDateOf(Instant.parse("2026-09-24T16:00:01Z")))
                .isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(CutoffClock.batchDateOf(Instant.parse("2026-09-24T23:59:59Z")))
                .isEqualTo(LocalDate.of(2026, 9, 25));
    }
}
