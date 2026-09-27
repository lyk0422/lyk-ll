package com.example.starter.pesticide;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结构化指纹单元测试：长度前缀编码无歧义，合法文本含分隔符也须区分。
 */
class FingerprintTest {

    @Test
    void 结构化指纹区分含分隔符文本() {
        // 朴素拼接会碰撞的组合：a|b + c 与 a + b|c
        assertThat(Fingerprint.structured("a|b", "c"))
                .isNotEqualTo(Fingerprint.structured("a", "b|c"));
        // 长度前缀自身的混淆：1:a + b 与 1 + a:b
        assertThat(Fingerprint.structured("1:a", "b"))
                .isNotEqualTo(Fingerprint.structured("1", "a:b"));
        // 分段数不同须区分
        assertThat(Fingerprint.structured("x", ""))
                .isNotEqualTo(Fingerprint.structured("x"));
        assertThat(Fingerprint.structured("ab", "c"))
                .isNotEqualTo(Fingerprint.structured("a", "bc"));
    }

    @Test
    void 结构化指纹同参一致() {
        assertThat(Fingerprint.structured("a", "b", "c"))
                .isEqualTo(Fingerprint.structured("a", "b", "c"));
        assertThat(Fingerprint.structured((String) null))
                .isEqualTo(Fingerprint.structured(""));
        assertThat(Fingerprint.structured("包含|分隔符的合法文本"))
                .isEqualTo(Fingerprint.structured("包含|分隔符的合法文本"));
    }

    @Test
    void 旧版指纹行为保留不变() {
        // 旧入口继续使用 of：拼接后哈希，不引入结构化变更
        assertThat(Fingerprint.of("a", "b")).isEqualTo(Fingerprint.of("a", "b"));
        assertThat(Fingerprint.of("a", "b")).isNotEqualTo(Fingerprint.of("a", "c"));
    }
}
