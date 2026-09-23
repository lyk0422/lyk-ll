package com.example.starter.spectrum.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FingerprintUtil} 规范化与差异性单元测试。
 */
class FingerprintUtilTest {

    @Test
    void samePartsSameFingerprint() {
        assertThat(FingerprintUtil.sha256("CREATE", "A,B", "100", "200"))
                .isEqualTo(FingerprintUtil.sha256("CREATE", "A,B", "100", "200"));
    }

    @Test
    void differentPartProducesDifferentFingerprint() {
        assertThat(FingerprintUtil.sha256("CREATE", "A,B", "100", "200"))
                .isNotEqualTo(FingerprintUtil.sha256("CREATE", "A,B", "100", "201"));
    }

    @Test
    void fingerprintIs64HexChars() {
        assertThat(FingerprintUtil.sha256("x")).matches("[0-9a-f]{64}");
    }
}
