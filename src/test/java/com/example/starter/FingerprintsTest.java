package com.example.starter;

import com.example.starter.util.Fingerprints;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 结构化指纹单元测试：字段值包含分隔符时仍须区分，编码无歧义。
 */
class FingerprintsTest {

    @Test
    void structuredDistinguishesSeparatorInsideValue() {
        // 单字段含竖线 vs 两个字段：拼接式编码会混淆，结构化编码必须区分
        assertNotEquals(Fingerprints.structured("a|b"), Fingerprints.structured("a", "b"));
        // 单字段含冒号与长度前缀样式文本 vs 两个字段
        assertNotEquals(Fingerprints.structured("1:a"), Fingerprints.structured("1", "a"));
        // 空串字段与缺失字段（null）须区分
        assertNotEquals(Fingerprints.structured(""), Fingerprints.structured((Object) null));
        // 字段顺序不同须区分
        assertNotEquals(Fingerprints.structured("ab", "c"), Fingerprints.structured("c", "ab"));
    }

    @Test
    void structuredIsDeterministicForSameInputs() {
        assertEquals(Fingerprints.structured("req-1", 42L, "方案|含分隔符", null),
                Fingerprints.structured("req-1", 42L, "方案|含分隔符", null));
        assertEquals(64, Fingerprints.structured("req-1").length());
    }
}
