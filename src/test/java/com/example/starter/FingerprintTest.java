package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.starter.service.IdempotencyService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等指纹结构化无歧义：合法文本含分隔符也须区分；版本、集合与精确时刻都参与指纹。
 */
class FingerprintTest {

    private final IdempotencyService idempotency =
            new IdempotencyService(null, new ObjectMapper(), Clock.systemUTC());

    private String fp(String operation, String requestId, Map<String, Object> payload) {
        return idempotency.fingerprint(operation, requestId, payload);
    }

    @Test
    void separatorInsideText_stillDistinguishable() {
        // 朴素拼接 "A|B|C" 会撞车；结构化指纹须区分分隔符落在哪个字段
        Map<String, Object> p1 = new LinkedHashMap<>();
        p1.put("dyeCode", "A|B");
        p1.put("lotCode", "C");
        Map<String, Object> p2 = new LinkedHashMap<>();
        p2.put("dyeCode", "A");
        p2.put("lotCode", "B|C");
        assertNotEquals(fp("OP", "r1", p1), fp("OP", "r1", p2));
    }

    @Test
    void sameParams_sameFingerprint_regardlessOfKeyOrderAndNumberScale() {
        Map<String, Object> p1 = new LinkedHashMap<>();
        p1.put("lotNo", "L1");
        p1.put("potency", new BigDecimal("1.0"));
        Map<String, Object> p2 = new LinkedHashMap<>();
        p2.put("potency", new BigDecimal("1.00"));
        p2.put("lotNo", "L1");
        assertEquals(fp("OP", "r1", p1), fp("OP", "r1", p2));
    }

    @Test
    void versionCollectionAndExactMoment_allCovered() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("dyeCode", "D-RED");
        item.put("lotCode", "LOT-1");
        item.put("potencyVersion", 1);
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("lotNo", "L1");
        base.put("expectedVersion", 1);
        base.put("validUntil", Instant.parse("2026-12-31T16:00:00Z"));
        base.put("items", List.of(item));

        Map<String, Object> same = new LinkedHashMap<>(base);
        assertEquals(fp("OP", "r1", base), fp("OP", "r1", same));

        Map<String, Object> otherVersion = new LinkedHashMap<>(base);
        otherVersion.put("expectedVersion", 2);
        assertNotEquals(fp("OP", "r1", base), fp("OP", "r1", otherVersion));

        Map<String, Object> otherMoment = new LinkedHashMap<>(base);
        otherMoment.put("validUntil", Instant.parse("2026-12-31T16:00:01Z"));
        assertNotEquals(fp("OP", "r1", base), fp("OP", "r1", otherMoment));

        Map<String, Object> otherItem = new LinkedHashMap<>();
        otherItem.put("dyeCode", "D-RED");
        otherItem.put("lotCode", "LOT-1");
        otherItem.put("potencyVersion", 2);
        Map<String, Object> otherCollection = new LinkedHashMap<>(base);
        otherCollection.put("items", List.of(otherItem));
        assertNotEquals(fp("OP", "r1", base), fp("OP", "r1", otherCollection));

        // 操作类型与 requestId 同样参与指纹
        assertNotEquals(fp("OP", "r1", base), fp("OP", "r2", base));
        assertNotEquals(fp("OP", "r1", base), fp("OTHER", "r1", base));
    }
}
