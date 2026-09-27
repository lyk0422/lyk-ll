package com.example.starter.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 结构化指纹单测：长度前缀编码保证含分隔符的合法文本也无歧义。
 */
class FingerprintTest {

    @Test
    @DisplayName("相同输入指纹稳定且为 64 位十六进制")
    void deterministicSha256Shape() {
        String h1 = Fingerprints.struct("req-1", "WF-DEMO", "5000");
        String h2 = Fingerprints.struct("req-1", "WF-DEMO", "5000");
        assertThat(h1).isEqualTo(h2);
        assertThat(h1).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("含竖线分隔符的字段切分不同：结构不同则指纹不同")
    void pipeInsideValueDoesNotCollideFields() {
        // 朴素字符串拼接会把 ("a|b","c") 与 ("a","b|c") 压成同样的串，长度前缀编码必须区分。
        assertThat(Fingerprints.struct("a|b", "c"))
                .isNotEqualTo(Fingerprints.struct("a", "b|c"));
    }

    @Test
    @DisplayName("无分隔符的相邻字段粘连也必须区分")
    void adjacentValuesWithoutDelimitersDoNotCollide() {
        assertThat(Fingerprints.struct("ab", "c"))
                .isNotEqualTo(Fingerprints.struct("a", "bc"));
        assertThat(Fingerprints.struct("12", "34"))
                .isNotEqualTo(Fingerprints.struct("1", "234"));
    }

    @Test
    @DisplayName("含冒号与长度数字样文本的字段组合无歧义")
    void colonAndDigitLikeValuesRemainDistinguishable() {
        assertThat(Fingerprints.struct("1:x", "y"))
                .isNotEqualTo(Fingerprints.struct("1", "x:y"));
    }

    @Test
    @DisplayName("集合顺序敏感：机组计划顺序变化得到不同指纹")
    void orderedCollectionsAreOrderSensitive() {
        assertThat(Fingerprints.struct("T-01", "T-02"))
                .isNotEqualTo(Fingerprints.struct("T-02", "T-01"));
    }
}
