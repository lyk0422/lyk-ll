package com.example.starter.payment.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class FingerprintsTest {

    @Test
    void sameInputsProduceSameFingerprint() {
        assertThat(Fingerprints.of("r1", "S1", new BigDecimal("10.00"), "M001"))
                .isEqualTo(Fingerprints.of("r1", "S1", new BigDecimal("10.00"), "M001"));
    }

    @Test
    void numericEquivalentAmountsProduceSameFingerprint() {
        assertThat(Fingerprints.of("r1", "S1", new BigDecimal("10.00"), "M001"))
                .isEqualTo(Fingerprints.of("r1", "S1", new BigDecimal("10"), "M001"));
    }

    @Test
    void differentAmountProducesDifferentFingerprint() {
        assertThat(Fingerprints.of("r1", "S1", new BigDecimal("10.00"), "M001"))
                .isNotEqualTo(Fingerprints.of("r1", "S1", new BigDecimal("11.00"), "M001"));
    }

    @Test
    void differentSerialProducesDifferentFingerprint() {
        assertThat(Fingerprints.of("r1", "S1", new BigDecimal("10.00"), "M001"))
                .isNotEqualTo(Fingerprints.of("r1", "S2", new BigDecimal("10.00"), "M001"));
    }

    @Test
    void differentMerchantProducesDifferentFingerprint() {
        assertThat(Fingerprints.of("r1", "S1", new BigDecimal("10.00"), "M001"))
                .isNotEqualTo(Fingerprints.of("r1", "S1", new BigDecimal("10.00"), "M002"));
    }
}
