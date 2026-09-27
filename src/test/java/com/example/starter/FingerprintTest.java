package com.example.starter;

import com.example.starter.service.Fingerprints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 幂等指纹：长度前缀结构化编码，合法文本含分隔符（空格/冒号/分号）也须区分，相同输入稳定。
 */
class FingerprintTest {

    @Test
    @DisplayName("含分隔符的合法文本不会与别的字段组合混淆")
    void lengthPrefixedEncodingIsUnambiguous() {
        // 朴素空格拼接下两者同为 "k1=a b k2=c"，结构化编码必须区分
        assertNotEquals(Fingerprints.of("k1=a b", "k2=c"), Fingerprints.of("k1=a", "b k2=c"));
        // 值内冒号/分号不破坏字段边界
        assertNotEquals(Fingerprints.of("k=a;b"), Fingerprints.of("k=a", "b"));
        assertNotEquals(Fingerprints.of("note=x y"), Fingerprints.of("note=x", "y"));
        assertNotEquals(Fingerprints.of("a=1:2"), Fingerprints.of("a=1", "2"));
    }

    @Test
    @DisplayName("相同输入指纹稳定，字段顺序敏感")
    void fingerprintIsStableAndOrderSensitive() {
        assertEquals(Fingerprints.of("a=1", "b=2"), Fingerprints.of("a=1", "b=2"));
        assertNotEquals(Fingerprints.of("a=1", "b=2"), Fingerprints.of("b=2", "a=1"));
        assertEquals(64, Fingerprints.of("a=1").length(), "SHA-256 十六进制摘要长度");
    }
}
