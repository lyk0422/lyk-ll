package com.example.starter.feed.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * requestId 幂等指纹测试：覆盖全部业务字段、键序无关、列表顺序敏感。
 */
class FingerprintUtilTest {

    @Test
    void samePayloadProducesSameFingerprint() {
        Map<String, Object> p1 = new LinkedHashMap<>();
        p1.put("requestId", "REQ-1");
        p1.put("batchNo", "B-1");
        p1.put("applyAtUtc", "2026-09-10T00:00:00Z");

        Map<String, Object> p2 = new LinkedHashMap<>();
        p2.put("applyAtUtc", "2026-09-10T00:00:00Z");
        p2.put("requestId", "REQ-1");
        p2.put("batchNo", "B-1");

        // 键插入顺序不同，但规范化后指纹一致。
        assertThat(FingerprintUtil.sha256(p1)).isEqualTo(FingerprintUtil.sha256(p2));
    }

    @Test
    void differentFieldValueProducesDifferentFingerprint() {
        Map<String, Object> p1 = Map.of("requestId", "REQ-1", "batchNo", "B-1");
        Map<String, Object> p2 = Map.of("requestId", "REQ-1", "batchNo", "B-2");
        assertThat(FingerprintUtil.sha256(p1)).isNotEqualTo(FingerprintUtil.sha256(p2));
    }

    @Test
    void differentRequestIdProducesDifferentFingerprint() {
        Map<String, Object> p1 = Map.of("requestId", "REQ-1", "batchNo", "B-1");
        Map<String, Object> p2 = Map.of("requestId", "REQ-2", "batchNo", "B-1");
        assertThat(FingerprintUtil.sha256(p1)).isNotEqualTo(FingerprintUtil.sha256(p2));
    }

    @Test
    void listOrderIsSignificant() {
        Object p1 = Map.of("items", List.of("A", "B"));
        Object p2 = Map.of("items", List.of("B", "A"));
        assertThat(FingerprintUtil.sha256(p1)).isNotEqualTo(FingerprintUtil.sha256(p2));
    }
}
