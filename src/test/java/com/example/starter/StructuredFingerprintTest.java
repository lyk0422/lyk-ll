package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.starter.support.Fingerprints;

/**
 * 结构化指纹：长度前缀编码无歧义，合法文本含分隔符也须区分；集合与空值编码确定。
 */
class StructuredFingerprintTest {

    @Test
    void 字段文本含分隔符仍可区分() {
        assertNotEquals(Fingerprints.structured("a|b", "c"), Fingerprints.structured("a", "b|c"));
        assertNotEquals(Fingerprints.structured("ab", "c"), Fingerprints.structured("a", "bc"));
        assertNotEquals(Fingerprints.structured("1:x"), Fingerprints.structured("1", "x"));
    }

    @Test
    void 同参指纹稳定且空值与空串区分() {
        assertEquals(Fingerprints.structured("req", 1L, List.of(2L, 3L), null),
                Fingerprints.structured("req", 1L, List.of(2L, 3L), null));
        assertNotEquals(Fingerprints.structured((Object) null), Fingerprints.structured(""));
        assertNotEquals(Fingerprints.structured(List.of(1L, 2L)), Fingerprints.structured(List.of(2L, 1L)));
        assertNotEquals(Fingerprints.structured(List.of(1L, 2L)), Fingerprints.structured(1L, 2L));
    }
}
