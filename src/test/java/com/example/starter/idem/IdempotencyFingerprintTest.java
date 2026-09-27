package com.example.starter.idem;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 结构化指纹单元测试：长度前缀编码保证字段含分隔符、空串、null 与集合边界均可无歧义区分。
 */
class IdempotencyFingerprintTest {

    @Test
    void sameFieldsProduceSameFingerprint() {
        String f1 = IdempotencyService.structuredFingerprint("EP", "r1",
                List.of("ticket", "1", "2026-09-26T01:00:00Z", "TW-0001"));
        String f2 = IdempotencyService.structuredFingerprint("EP", "r1",
                List.of("ticket", "1", "2026-09-26T01:00:00Z", "TW-0001"));
        assertEquals(f1, f2);
    }

    @Test
    void fieldsContainingSeparatorAreDistinguished() {
        // 旧的 '|' 拼接会把 ("a|b","c") 与 ("a","b|c") 混同；长度前缀编码必须区分
        String split1 = IdempotencyService.structuredFingerprint("EP", "r1", List.of("a|b", "c"));
        String split2 = IdempotencyService.structuredFingerprint("EP", "r1", List.of("a", "b|c"));
        assertNotEquals(split1, split2);
    }

    @Test
    void emptyAndNullAreDistinguished() {
        String empty = IdempotencyService.structuredFingerprint("EP", "r1", List.of(""));
        String nullField = IdempotencyService.structuredFingerprint("EP", "r1",
                Collections.singletonList(null));
        String one = IdempotencyService.structuredFingerprint("EP", "r1", List.of("1"));
        assertNotEquals(empty, nullField);
        assertNotEquals(empty, one);
    }

    @Test
    void collectionBoundariesAreDistinguished() {
        // ("a,b", 1 元素) 与 ("a","b", 2 元素) 不得同指纹
        String oneElement = IdempotencyService.structuredFingerprint("EP", "r1", List.of("a,b"));
        String twoElements = IdempotencyService.structuredFingerprint("EP", "r1", List.of("a", "b"));
        assertNotEquals(oneElement, twoElements);
    }

    @Test
    void requestIdAndEndpointArePartOfFingerprint() {
        List<String> fields = List.of("v1");
        assertNotEquals(
                IdempotencyService.structuredFingerprint("EP", "r1", fields),
                IdempotencyService.structuredFingerprint("EP", "r2", fields));
        assertNotEquals(
                IdempotencyService.structuredFingerprint("EP1", "r1", fields),
                IdempotencyService.structuredFingerprint("EP2", "r1", fields));
    }
}
