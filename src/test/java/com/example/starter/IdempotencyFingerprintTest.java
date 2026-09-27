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
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 幂等指纹的纯单元测试（无数据库）：指纹须结构化无歧义——
 * 合法文本含分隔符、键序差异、集合元素顺序、数值规范化与精确时刻均须可区分。
 */
class IdempotencyFingerprintTest {

    private final IdempotencyService idempotency = new IdempotencyService(
            null, new ObjectMapper().registerModule(new JavaTimeModule()), Clock.systemUTC());

    private String fp(String requestId, Map<String, Object> payload) {
        return idempotency.fingerprint("OP", requestId, payload);
    }

    @Test
    void delimiterInsideLegalText_isStructurallyDistinguished() {
        // 单值内含逗号/冒号，与拆成两个键的结构产生不同指纹
        Map<String, Object> one = Map.of("k", "a,b:c");
        Map<String, Object> split = new LinkedHashMap<>();
        split.put("k", "a");
        split.put("b", "c");
        assertNotEquals(fp("r1", one), fp("r1", split));
    }

    @Test
    void keyOrderDoesNotMatter_butValueShapeDoes() {
        Map<String, Object> ab = new LinkedHashMap<>();
        ab.put("a", "1");
        ab.put("b", "2");
        Map<String, Object> ba = new LinkedHashMap<>();
        ba.put("b", "2");
        ba.put("a", "1");
        assertEquals(fp("r2", ab), fp("r2", ba), "Map 键序规范化后同参指纹相同");

        // 数字 1 与字符串 "1" 结构不同
        assertNotEquals(fp("r2", Map.of("a", 1)), fp("r2", Map.of("a", "1")));
    }

    @Test
    void listElementOrderAndNesting_areSignificant() {
        Map<String, Object> xy = Map.of("items", List.of("X", "Y"));
        Map<String, Object> yx = Map.of("items", List.of("Y", "X"));
        assertNotEquals(fp("r3", xy), fp("r3", yx), "集合（List）元素顺序参与指纹");

        // 分隔符文本：["X","Y,Z"] 与 ["X,Y","Z"] 不同
        Map<String, Object> nested1 = Map.of("items", List.of("X", "Y,Z"));
        Map<String, Object> nested2 = Map.of("items", List.of("X,Y", "Z"));
        assertNotEquals(fp("r4", nested1), fp("r4", nested2));
    }

    @Test
    void bigDecimalTrailingZeros_areNormalized() {
        Map<String, Object> p1 = Map.of("g", new BigDecimal("1.000"));
        Map<String, Object> p2 = Map.of("g", new BigDecimal("1.0"));
        assertEquals(fp("r5", p1), fp("r5", p2), "1.000 与 1.0 数值等价应同指纹");

        // 1.0 与 1.1 不同
        assertNotEquals(fp("r5", p2), fp("r5", Map.of("g", new BigDecimal("1.1"))));
    }

    @Test
    void requestId_operation_andPreciseInstant_participateInFingerprint() {
        Map<String, Object> payload = Map.of("at", Instant.parse("2026-06-01T00:00:00Z"));
        assertNotEquals(fp("req-A", payload), fp("req-B", payload), "requestId 不同指纹不同");
        assertNotEquals(
                idempotency.fingerprint("OP-A", "req", payload),
                idempotency.fingerprint("OP-B", "req", payload), "操作类型不同指纹不同");
        assertNotEquals(
                fp("req", Map.of("at", Instant.parse("2026-06-01T00:00:00Z"))),
                fp("req", Map.of("at", Instant.parse("2026-06-01T00:00:01Z"))),
                "相差 1 秒的精确时刻指纹不同");
    }
}
