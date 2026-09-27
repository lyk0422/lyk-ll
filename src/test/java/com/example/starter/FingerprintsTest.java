package com.example.starter;

import java.util.List;

import com.example.starter.util.Fingerprints;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 幂等指纹结构测试：合法文本含分隔符、字段拆分位置不同、集合与展开参数均须区分。
 */
class FingerprintsTest {

    @Test
    void samePartsProduceSameFingerprint() {
        assertEquals(Fingerprints.of("req-1", 42L, "审批人甲"), Fingerprints.of("req-1", 42L, "审批人甲"));
    }

    @Test
    void separatorInsideTextIsDistinguished() {
        // 旧式分隔符拼接下两者同为 "a|b"，结构化编码必须区分
        assertNotEquals(Fingerprints.of("a|b"), Fingerprints.of("a", "b"));
        assertNotEquals(Fingerprints.of("ab", "c"), Fingerprints.of("a", "bc"));
        assertNotEquals(Fingerprints.of("x:y"), Fingerprints.of("x", "y"));
    }

    @Test
    void nullAndEmptyAreDistinguished() {
        assertNotEquals(Fingerprints.of((Object) null), Fingerprints.of(""));
        assertNotEquals(Fingerprints.of("a", null), Fingerprints.of("a"));
    }

    @Test
    void collectionsAreStructuredAndOrderSensitive() {
        // 集合与等长展开参数列表区分；集合元素顺序不同指纹不同
        assertNotEquals(Fingerprints.of(List.of("a", "b")), Fingerprints.of("a", "b"));
        assertNotEquals(Fingerprints.of(List.of("a", "b")), Fingerprints.of(List.of("b", "a")));
        assertEquals(Fingerprints.of(List.of("a", "b")), Fingerprints.of(List.of("a", "b")));
        assertNotEquals(Fingerprints.of(List.of("a|b")), Fingerprints.of(List.of("a", "b")));
    }
}
