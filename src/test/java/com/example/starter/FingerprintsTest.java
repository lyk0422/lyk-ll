package com.example.starter;

import java.math.BigDecimal;

import com.example.starter.util.Fingerprints;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 结构化指纹单元测试：合法文本含分隔符也可区分，null 与文本不混淆，
 * BigDecimal 去尾零同值同指纹；旧 of 方法行为保留。
 */
class FingerprintsTest {

    @Test
    void structuredDistinguishesTextContainingSeparator() {
        assertNotEquals(Fingerprints.structured("a|b", "c"), Fingerprints.structured("a", "b|c"));
        assertNotEquals(Fingerprints.structured("ab", "c"), Fingerprints.structured("a", "bc"));
        assertNotEquals(Fingerprints.structured("V1:x", "y"), Fingerprints.structured("x", "y"));
    }

    @Test
    void structuredDistinguishesNullFromText() {
        assertNotEquals(Fingerprints.structured((Object) null), Fingerprints.structured("N;"));
        assertNotEquals(Fingerprints.structured((Object) null), Fingerprints.structured("<null>"));
        assertNotEquals(Fingerprints.structured((Object) null, "a"),
                Fingerprints.structured("a", (Object) null));
    }

    @Test
    void structuredNormalizesBigDecimalTrailingZeros() {
        assertEquals(Fingerprints.structured(new BigDecimal("1.0")),
                Fingerprints.structured(new BigDecimal("1.00")));
        assertNotEquals(Fingerprints.structured(new BigDecimal("1.0")),
                Fingerprints.structured(new BigDecimal("1.01")));
    }

    @Test
    void structuredIsDeterministic() {
        assertEquals(Fingerprints.structured("req-1", 42L, new BigDecimal("0.5"), "2026-09-26T00:00:00Z"),
                Fingerprints.structured("req-1", 42L, new BigDecimal("0.5"), "2026-09-26T00:00:00Z"));
    }

    @Test
    void legacyOfRemainsUnchanged() {
        assertEquals(Fingerprints.of("a", null, 1), Fingerprints.of("a", null, 1));
        assertNotEquals(Fingerprints.of("a", 1), Fingerprints.of("a", 2));
    }
}
