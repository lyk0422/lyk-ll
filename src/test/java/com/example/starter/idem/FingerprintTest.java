package com.example.starter.idem;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 结构化指纹无歧义验证：长度前缀编码必须区分“分隔符落在文本内”与“分隔符落在字段边界”。
 */
class FingerprintTest {

    @Test
    void delimiterInsideTextCannotForgeFieldBoundary() {
        // 朴素拼接下两组字段同为 endpoint|r|a|b|c；长度前缀编码必须区分。
        String group1 = IdempotencyService.fingerprintStructured("E", "r", List.of("a", "b|c"));
        String group2 = IdempotencyService.fingerprintStructured("E", "r", List.of("a|b", "c"));
        assertNotEquals(group1, group2);
    }

    @Test
    void listBoundaryIsUnambiguous() {
        // 单元素 ["a,b"] 与双元素 ["a","b"] 朴素逗号拼接结果相同，结构化编码必须区分。
        String oneElement = IdempotencyService.fingerprintStructured("E", "r", List.of(List.of("a,b")));
        String twoElements = IdempotencyService.fingerprintStructured("E", "r", List.of(List.of("a", "b")));
        assertNotEquals(oneElement, twoElements);
    }

    @Test
    void sameStructureProducesSameFingerprint() {
        String first = IdempotencyService.fingerprintStructured("E", "r",
                List.of(1L, List.of("JDX-1001", "JDX-1002"), "2026-09-26T01:00:00Z"));
        String second = IdempotencyService.fingerprintStructured("E", "r",
                List.of(1L, List.of("JDX-1001", "JDX-1002"), "2026-09-26T01:00:00Z"));
        assertEquals(first, second);
    }
}
