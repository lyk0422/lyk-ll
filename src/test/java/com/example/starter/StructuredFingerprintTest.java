package com.example.starter;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.starter.idem.IdempotencyService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 结构化幂等指纹单元测试：长度前缀编码保证字段边界无歧义，
 * 合法文本含分隔符（'|'、':'）也须区分。
 */
class StructuredFingerprintTest {

    @Test
    void sameInputProducesSameFingerprint() {
        String f1 = IdempotencyService.structuredFingerprint("EP", "req-1", List.of("a", "b"));
        String f2 = IdempotencyService.structuredFingerprint("EP", "req-1", List.of("a", "b"));
        assertEquals(f1, f2);
    }

    @Test
    void distinguishesFieldsContainingDelimiter() {
        // 朴素 '|' 拼接会得到相同串的两组参数，结构化指纹必须区分
        String f1 = IdempotencyService.structuredFingerprint("EP", "req-1", List.of("a|b", "c"));
        String f2 = IdempotencyService.structuredFingerprint("EP", "req-1", List.of("a", "b|c"));
        assertNotEquals(f1, f2);
    }

    @Test
    void distinguishesFieldsContainingColonAndLengthPrefix() {
        String f1 = IdempotencyService.structuredFingerprint("EP", "req-1", List.of("2:a", "b"));
        String f2 = IdempotencyService.structuredFingerprint("EP", "req-1", List.of("2", "a;b"));
        assertNotEquals(f1, f2);
    }

    @Test
    void distinguishesFieldBoundaries() {
        String f1 = IdempotencyService.structuredFingerprint("EP", "req-1", List.of("ab", "c"));
        String f2 = IdempotencyService.structuredFingerprint("EP", "req-1", List.of("a", "bc"));
        assertNotEquals(f1, f2);
    }

    @Test
    void distinguishesEndpointAndRequestId() {
        String base = IdempotencyService.structuredFingerprint("EP", "req-1", List.of("a"));
        assertNotEquals(base, IdempotencyService.structuredFingerprint("EP2", "req-1", List.of("a")));
        assertNotEquals(base, IdempotencyService.structuredFingerprint("EP", "req-2", List.of("a")));
        assertNotEquals(base, IdempotencyService.structuredFingerprint("EP", "req-1", List.of("a", "")));
    }
}
