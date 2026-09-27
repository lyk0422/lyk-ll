package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import com.example.starter.service.IdempotencyService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等指纹结构化无歧义：合法文本含分隔符也须区分；数值规范化等价。
 * 纯单元测试，不启动 Spring 上下文。
 */
class IdempotencyFingerprintTest {

    private final IdempotencyService idempotency = new IdempotencyService(
            null, new ObjectMapper(), Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),
                    ZoneId.of("Asia/Shanghai")));

    @Test
    void fingerprint_separatorInsideText_stillDistinct() {
        // 朴素拼接 "B-RED|1" 会碰撞的两种结构，JSON 结构化指纹必须区分
        String f1 = idempotency.fingerprint("OP", "req-1",
                Map.of("batchNo", "B-RED|1"));
        Map<String, Object> nested = new TreeMap<>();
        nested.put("batchNo", "B-RED");
        nested.put("factorVersion", "1");
        String f2 = idempotency.fingerprint("OP", "req-1", nested);
        assertNotEquals(f1, f2, "文本含分隔符不得与结构化字段碰撞");

        // 集合边界：["a","b"] 与 ["a,b"] 必须区分
        String f3 = idempotency.fingerprint("OP", "req-1", Map.of("items", List.of("a", "b")));
        String f4 = idempotency.fingerprint("OP", "req-1", Map.of("items", List.of("a,b")));
        assertNotEquals(f3, f4, "集合元素边界不得被分隔符文本混淆");

        // 引号/转义字符文本同样无歧义
        String f5 = idempotency.fingerprint("OP", "req-1", Map.of("note", "a\",\"b"));
        String f6 = idempotency.fingerprint("OP", "req-1", Map.of("note", "a", "x", "b"));
        assertNotEquals(f5, f6);
    }

    @Test
    void fingerprint_sameParamsSameHash_numericNormalized() {
        Map<String, Object> a = Map.of("factor", new BigDecimal("1.250000"), "availableG",
                new BigDecimal("3000.000"));
        Map<String, Object> b = Map.of("availableG", new BigDecimal("3000.00"),
                "factor", new BigDecimal("1.2500"));
        assertEquals(idempotency.fingerprint("OP", "req-1", a),
                idempotency.fingerprint("OP", "req-1", b),
                "键序与数值尾零不影响同参判定");

        String differentKey = idempotency.fingerprint("OP", "req-2", a);
        assertNotEquals(idempotency.fingerprint("OP", "req-1", a), differentKey,
                "requestId 参与指纹");
        String differentOp = idempotency.fingerprint("OP2", "req-1", a);
        assertNotEquals(idempotency.fingerprint("OP", "req-1", a), differentOp,
                "操作类型参与指纹");
    }
}
