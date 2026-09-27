package com.example.starter.radio.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 结构化幂等指纹无歧义性单测：长度前缀编码必须区分含分隔符的合法文本与字段切分，
 * 并区分字符串与数值类型。
 */
class FingerprintsTest {

    @Test
    void structuredDistinguishesDelimiterInsideTextField() {
        // 单字段文本内含 '|'，不得等价于两个字段的拼接
        String single = Fingerprints.structured("a|b");
        String two = Fingerprints.structured("a", "b");
        assertThat(single).isNotEqualTo(two);
    }

    @Test
    void structuredDistinguishesStringAndNumber() {
        assertThat(Fingerprints.structured("1")).isNotEqualTo(Fingerprints.structured(1));
        assertThat(Fingerprints.structured("1")).isNotEqualTo(Fingerprints.structured(1L));
    }

    @Test
    void structuredDistinguishesFieldOrderAndSets() {
        assertThat(Fingerprints.structured("SUBMIT", 1, 2))
                .isNotEqualTo(Fingerprints.structured("SUBMIT", 2, 1));
        // 集合/多字段内容相同但成员不同必须可区分
        assertThat(Fingerprints.structured("alice", "bob"))
                .isNotEqualTo(Fingerprints.structured("alice", "carol"));
    }

    @Test
    void structuredIsStableForSameArguments() {
        assertThat(Fingerprints.structured("SUBMIT", "req-1", 7L, 1, 1, 3960L))
                .isEqualTo(Fingerprints.structured("SUBMIT", "req-1", 7L, 1, 1, 3960L));
    }

    @Test
    void structuredDistinguishesSeparatorLikeLengthPrefix() {
        // 文本中伪造 "长度:负载" 形态也不能越界成另一个字段
        assertThat(Fingerprints.structured("s1:x")).isNotEqualTo(Fingerprints.structured("x"));
    }
}
