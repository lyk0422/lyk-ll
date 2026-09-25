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
}
