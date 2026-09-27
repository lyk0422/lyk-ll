package com.example.starter.radio.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 结构化指纹无歧义性：合法文本即便包含分隔符（'|'、换行）或可跨字段重组，
 * 长度前缀编码也必须产生不同指纹，杜绝跨字段碰撞。
 */
class FingerprintsTest {

    @Test
    void structuredEncodingDistinguishesDelimiterInsideValues() {
        // 朴素 '|' 拼接会令下列两组产生同一串；结构化长度前缀必须区分
        String a = Fingerprints.structured(List.of("a|b", "c"));
        String b = Fingerprints.structured(List.of("a", "b|c"));
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void structuredEncodingDistinguishesNewlineAndEmptyFieldRearrangement() {
        String a = Fingerprints.structured(List.of("x\ny", ""));
        String b = Fingerprints.structured(List.of("x", "\ny"));
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void sameFieldsProduceSameFingerprint() {
        String first = Fingerprints.structured(List.of("SUBMIT_CHANGE", "req-1", "7", "1", "4000"));
        String second = Fingerprints.structured(List.of("SUBMIT_CHANGE", "req-1", "7", "1", "4000"));
        assertThat(first).isEqualTo(second);
    }

    @Test
    void anyFieldChangeProducesDifferentFingerprint() {
        List<String> base = List.of("SUBMIT_CHANGE", "req-1", "7", "1", "4000");
        String fingerprint = Fingerprints.structured(base);
        assertThat(Fingerprints.structured(List.of("SUBMIT_CHANGE", "req-1", "7", "1", "4001")))
                .isNotEqualTo(fingerprint);
        assertThat(Fingerprints.structured(List.of("SUBMIT_CHANGE", "req-1", "7", "2", "4000")))
                .isNotEqualTo(fingerprint);
    }
}
