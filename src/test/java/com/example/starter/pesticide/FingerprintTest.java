package com.example.starter.pesticide;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结构化幂等指纹测试：长度前缀编码保证合法文本含分隔符时仍无歧义。
 */
class FingerprintTest {

    @Test
    void 结构化指纹对分隔符文本无歧义() {
        // 朴素拼接会产生碰撞的输入，在结构化编码下必须不同
        assertThat(Fingerprint.structured(List.of("a|b", "c")))
                .isNotEqualTo(Fingerprint.structured(List.of("a", "b|c")));
        assertThat(Fingerprint.structured(List.of("a#b")))
                .isNotEqualTo(Fingerprint.structured(List.of("a", "b")));
        assertThat(Fingerprint.structured(List.of("ab", "c")))
                .isNotEqualTo(Fingerprint.structured(List.of("a", "bc")));
        // 元素个数参与编码：前缀相同的集合不会碰撞
        assertThat(Fingerprint.structured(List.of("1#a")))
                .isNotEqualTo(Fingerprint.structured(List.of("a")));
        assertThat(Fingerprint.structured(List.of("a", "b")))
                .isNotEqualTo(Fingerprint.structured(List.of("a", "b", "")));
    }

    @Test
    void 结构化指纹确定性且为空安全() {
        assertThat(Fingerprint.structured(List.of("毒死蜱", "S-001", "AG-A")))
                .isEqualTo(Fingerprint.structured(List.of("毒死蜱", "S-001", "AG-A")));
        assertThat(Fingerprint.structured(List.of()))
                .isEqualTo(Fingerprint.structured(List.of()));
        // null 以空串参与
        assertThat(Fingerprint.structured(java.util.Arrays.asList("a", null)))
                .isEqualTo(Fingerprint.structured(List.of("a", "")));
    }
}
