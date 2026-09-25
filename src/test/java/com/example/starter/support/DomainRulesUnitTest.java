package com.example.starter.support;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 速率复算与幂等指纹的纯单元测试，不启动 Spring。
 */
class DomainRulesUnitTest {

    @Test
    void rateExactlyEighteen_isNotOverspeed() {
        // 18 米 / 1 分钟 = 恰 18，合格
        BigDecimal rate = com.example.starter.dao.RecordRepository
                .rateOf("DESCENT", 0, 18, 1);
        assertEquals(0, rate.compareTo(BigDecimal.valueOf(18)));
        assertEquals(false, rate.compareTo(BigDecimal.valueOf(18)) > 0);
    }

    @Test
    void rateAboveEighteen_isOverspeed() {
        // 24 米 / 1 分钟 = 24 > 18，超速
        BigDecimal rate = com.example.starter.dao.RecordRepository
                .rateOf("ASCENT", 24, 0, 1);
        assertEquals(0, rate.compareTo(BigDecimal.valueOf(24)));
        assertEquals(true, rate.compareTo(BigDecimal.valueOf(18)) > 0);
    }

    @Test
    void bottomRateIsZero() {
        assertEquals(0, com.example.starter.dao.RecordRepository
                .rateOf("BOTTOM", 18, 18, 50).compareTo(BigDecimal.ZERO));
    }

    @Test
    void fingerprintStableForSameFields_differsWhenAnyFieldChanges() {
        Map<String, Object> a = Map.of("requestId", "X", "depthM", 18, "bottomMin", 50);
        Map<String, Object> b = Map.of("bottomMin", 50, "depthM", 18, "requestId", "X");
        assertEquals(Fingerprints.sha256(a), Fingerprints.sha256(b), "字段顺序不影响指纹");

        Map<String, Object> changed = Map.of("requestId", "X", "depthM", 18, "bottomMin", 51);
        assertNotEquals(Fingerprints.sha256(a), Fingerprints.sha256(changed), "业务字段变化指纹必须不同");

        Map<String, Object> otherKey = Map.of("requestId", "Y", "depthM", 18, "bottomMin", 50);
        assertNotEquals(Fingerprints.sha256(a), Fingerprints.sha256(otherKey), "requestId 变化指纹必须不同");
    }

    @Test
    void fingerprintHandlesInstant() {
        Map<String, Object> withNull = Map.of("requestId", "X", "at", java.time.Instant.parse("2026-09-25T00:00:00Z"));
        // 两次计算稳定，且不同时刻不同
        assertEquals(Fingerprints.sha256(withNull), Fingerprints.sha256(withNull));
        Map<String, Object> otherTime = Map.of("requestId", "X",
                "at", java.time.Instant.parse("2026-09-25T00:00:01Z"));
        assertNotEquals(Fingerprints.sha256(withNull), Fingerprints.sha256(otherTime));
    }
}
