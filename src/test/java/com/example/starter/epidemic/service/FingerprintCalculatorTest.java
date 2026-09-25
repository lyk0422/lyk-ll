package com.example.starter.epidemic.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等指纹单元测试：同参同指纹、异参异指纹、空值归一稳定。
 */
class FingerprintCalculatorTest {

    private final FingerprintCalculator calculator = new FingerprintCalculator();

    @Test
    void same_fields_produce_same_fingerprint() {
        String a = calculator.sha256("INITIAL", "r1", "P1", "MEASLES", "2026-09-25");
        String b = calculator.sha256("INITIAL", "r1", "P1", "MEASLES", "2026-09-25");
        assertThat(a).isEqualTo(b).hasSize(64);
    }

    @Test
    void different_fields_produce_different_fingerprint() {
        String a = calculator.sha256("INITIAL", "r1", "P1", "MEASLES", "2026-09-25");
        String b = calculator.sha256("INITIAL", "r1", "P1", "PLAGUE", "2026-09-25");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void null_and_blank_are_normalized_consistently() {
        assertThat(calculator.sha256("r", null, "")).isEqualTo(calculator.sha256("r", null, ""));
        assertThat(calculator.sha256("r", null, ""))
                .isNotEqualTo(calculator.sha256("r", "x", ""));
    }
}
