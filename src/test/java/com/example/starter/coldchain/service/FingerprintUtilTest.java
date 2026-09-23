package com.example.starter.coldchain.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class FingerprintUtilTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 9, 23, 8, 0);

    @Test
    void sameParamsProduceSameFingerprint() {
        String a = FingerprintUtil.sha256("S1", 1, "B", T, 10L);
        String b = FingerprintUtil.sha256("S1", 1, "B", T, 10L);
        assertEquals(a, b);
        assertEquals(64, a.length(), "SHA-256 十六进制长度应为 64");
    }

    @Test
    void differentVersionOrLastSequenceChangesFingerprint() {
        String base = FingerprintUtil.sha256("S1", 1, "B", T, 10L);
        assertNotEquals(base, FingerprintUtil.sha256("S1", 2, "B", T, 10L));
        assertNotEquals(base, FingerprintUtil.sha256("S1", 1, "B", T, 11L));
        assertNotEquals(base, FingerprintUtil.sha256("S1", 1, "C", T, 10L));
        assertNotEquals(base, FingerprintUtil.sha256("S1", 1, "B", T.plusSeconds(1), 10L));
        assertNotEquals(base, FingerprintUtil.sha256("S2", 1, "B", T, 10L));
    }
}
