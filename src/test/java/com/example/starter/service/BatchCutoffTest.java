package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** UTC 16:00 日切归属规则单元测试（不依赖 Spring 与数据库）。 */
class BatchCutoffTest {

    private final PaymentService paymentService = new PaymentService(null, null, null, null, null, null, null);

    @Test
    void beforeCutoffBelongsToSameDay() {
        Instant at = Instant.parse("2026-09-20T15:59:59Z");
        assertThat(paymentService.resolveBatchDate(at)).isEqualTo(LocalDate.of(2026, 9, 20));
    }

    @Test
    void exactlyAtCutoffBelongsToNextDay() {
        Instant at = Instant.parse("2026-09-20T16:00:00Z");
        assertThat(paymentService.resolveBatchDate(at)).isEqualTo(LocalDate.of(2026, 9, 21));
    }

    @Test
    void afterCutoffBelongsToNextDay() {
        Instant at = Instant.parse("2026-09-20T16:00:01Z");
        assertThat(paymentService.resolveBatchDate(at)).isEqualTo(LocalDate.of(2026, 9, 21));
    }

    @Test
    void midnightBelongsToSameDay() {
        Instant at = Instant.parse("2026-09-20T00:00:00Z");
        assertThat(paymentService.resolveBatchDate(at)).isEqualTo(LocalDate.of(2026, 9, 20));
    }

    @Test
    void lateNightAfterCutoffRollsToNextDay() {
        Instant at = Instant.parse("2026-09-20T23:59:59Z");
        assertThat(paymentService.resolveBatchDate(at)).isEqualTo(LocalDate.of(2026, 9, 21));
    }
}
