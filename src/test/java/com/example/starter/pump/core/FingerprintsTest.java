package com.example.starter.pump.core;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等指纹纯单元测试：同参同指纹、任一业务字段变化指纹不同、稳定可复算。
 */
class FingerprintsTest {

    private final LocalDateTime t = LocalDateTime.of(2026, 9, 26, 8, 0);

    @Test
    void sameRequestIdAndParams_produceSameFingerprint() {
        String a = Fingerprints.sha256("req-1", "ZN", "ORANGE", t);
        String b = Fingerprints.sha256("req-1", "ZN", "ORANGE", t);
        assertThat(a).isEqualTo(b).hasSize(64);
    }

    @Test
    void differentRequestId_producesDifferentFingerprint() {
        String a = Fingerprints.sha256("req-1", "ZN", "ORANGE", t);
        String b = Fingerprints.sha256("req-2", "ZN", "ORANGE", t);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void anyBusinessFieldChanged_producesDifferentFingerprint() {
        String base = Fingerprints.sha256("req-1", "ZN", "ORANGE", t);
        assertThat(Fingerprints.sha256("req-1", "ZB", "ORANGE", t)).isNotEqualTo(base);
        assertThat(Fingerprints.sha256("req-1", "ZN", "RED", t)).isNotEqualTo(base);
        assertThat(Fingerprints.sha256("req-1", "ZN", "ORANGE", t.plusMinutes(1))).isNotEqualTo(base);
    }

    @Test
    void nullAndEmptyAreStable() {
        assertThat(Fingerprints.sha256("r", null)).isEqualTo(Fingerprints.sha256("r", (Object) null));
    }

    @Test
    void structured_samePartsSameFingerprint() {
        String a = Fingerprints.structured("TIDE_READING", "req-1", "ZN",
                java.math.BigDecimal.ONE, 1);
        String b = Fingerprints.structured("TIDE_READING", "req-1", "ZN",
                java.math.BigDecimal.ONE, 1);
        assertThat(a).isEqualTo(b).hasSize(64);
    }

    @Test
    void structured_textContainingDelimitersCannotForgeFieldBoundary() {
        // 朴素 '|' 拼接下两组都规范化为 a|b|c 会碰撞；长度前缀编码按字段边界区分，不会碰撞
        String merged = Fingerprints.structured("a|b", "c");
        String split = Fingerprints.structured("a", "b|c");
        assertThat(merged).isNotEqualTo(split);
    }

    @Test
    void structured_anyFieldOrVersionChange_differs() {
        String base = Fingerprints.structured("CANDIDATE_CONFIRM", "req", 10L, 1, t);
        assertThat(Fingerprints.structured("CANDIDATE_CONFIRM", "req", 10L, 2, t))
                .isNotEqualTo(base);
        assertThat(Fingerprints.structured("CANDIDATE_CONFIRM", "req", 11L, 1, t))
                .isNotEqualTo(base);
        assertThat(Fingerprints.structured("CANDIDATE_CONFIRM", "req2", 10L, 1, t))
                .isNotEqualTo(base);
    }

    @Test
    void structured_numberScaleIsCanonical() {
        // 1.0 与 1.00 数值相等，规范化为同一串，保证同参同指纹
        assertThat(Fingerprints.structured(new java.math.BigDecimal("1.0")))
                .isEqualTo(Fingerprints.structured(new java.math.BigDecimal("1.00")));
    }
}
