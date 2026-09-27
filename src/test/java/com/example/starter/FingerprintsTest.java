package com.example.starter;

import java.util.List;

import com.example.starter.service.Fingerprints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 结构化指纹：合法文本即便包含分隔符（=、空格等）也不得与另一组字段产生相同摘要，
 * 长度前缀必须消除字段边界歧义。
 */
class FingerprintsTest {

    @Test
    @DisplayName("含分隔符的合法文本不会伪造字段对齐：不同分段结构摘要不同")
    void delimiterInsideValueCannotForgeBoundaries() {
        // 第一种分段：两段 ["a=b c", "d"]
        String split = Fingerprints.structured("a=b c", "d");
        // 第二种分段：一段 ["a=b", "c d"] —— 朴素拼接会与上面同串
        String merged = Fingerprints.structured("a=b", "c d");
        assertNotEquals(split, merged, "长度前缀编码必须区分内含分隔符的不同分段");

        // 集合：["a","b"] 与 ["a,b"] 朴素拼接同串，结构化编码须区分
        String twoElements = Fingerprints.structured(List.of("set"), List.of(List.of("a", "b")));
        String oneElement = Fingerprints.structured(List.of("set"), List.of(List.of("a,b")));
        assertNotEquals(twoElements, oneElement, "集合元素个数前缀必须区分分隔符注入");

        // 标量与集合边界：["a", ["b"]] 与 ["a","b"] 不同结构
        String scalarThenCollection = Fingerprints.structured(List.of("a"), List.of(List.of("b")));
        String twoScalars = Fingerprints.structured(List.of("a", "b"), List.of());
        assertNotEquals(scalarThenCollection, twoScalars, "标量与集合元素不得互相冒充");
    }
}
