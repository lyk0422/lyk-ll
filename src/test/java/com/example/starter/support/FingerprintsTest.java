package com.example.starter.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 结构化指纹单元测试：长度前缀编码保证字段边界无歧义，
 * 合法文本含分隔符（|、:）也不会与相邻字段粘连产生相同指纹。
 */
class FingerprintsTest {

    @Test
    @DisplayName("朴素分隔符拼接会冲突的字段组合，结构化指纹可区分")
    void structuredEncodingDisambiguatesSeparatorContainingText() {
        // 朴素 "a|b|c" 拼接下两组字段不可区分。
        String naive1 = "ORDER|" + "a|b" + "|" + "c";
        String naive2 = "ORDER|" + "a" + "|" + "b|c";
        assertThat(naive1).isEqualTo(naive2);

        String f1 = Fingerprints.structured("ORDER", List.of("a|b", "c"));
        String f2 = Fingerprints.structured("ORDER", List.of("a", "b|c"));
        assertThat(f1).isNotEqualTo(f2);
    }

    @Test
    @DisplayName("null 与空串编码不同；字段数不同编码不同")
    void nullEmptyAndArityAreDistinct() {
        String withNull = Fingerprints.structured("T", Arrays.asList("a", null));
        String withEmpty = Fingerprints.structured("T", List.of("a", ""));
        String single = Fingerprints.structured("T", List.of("a"));
        assertThat(withNull).isNotEqualTo(withEmpty);
        assertThat(withNull).isNotEqualTo(single);
        assertThat(withEmpty).isNotEqualTo(single);
    }

    @Test
    @DisplayName("相同输入指纹稳定；业务类型不同指纹不同")
    void deterministicAndBizTypeScoped() {
        String a1 = Fingerprints.structured("PLAN", List.of("k", "v"));
        String a2 = Fingerprints.structured("PLAN", List.of("k", "v"));
        String b = Fingerprints.structured("PLAN_ORDER", List.of("k", "v"));
        assertThat(a1).isEqualTo(a2);
        assertThat(a1).isNotEqualTo(b);
        assertThat(a1).hasSize(64);
    }

    @Test
    @DisplayName("编码含长度前缀，可逐字段无歧义切分")
    void encodeCarriesLengthPrefixes() {
        String encoded = Fingerprints.encode("BIZ", List.of("ab", "c:d"));
        assertThat(encoded).isEqualTo("3:BIZ2:ab3:c:d");
    }
}
