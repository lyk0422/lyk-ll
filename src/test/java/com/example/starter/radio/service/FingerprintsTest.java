package com.example.starter.radio.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 结构化幂等指纹单元测试：合法文本含分隔符也须与字段切分区分。
 */
class FingerprintsTest {

    @Test
    void sameFieldsProduceSameFingerprint() {
        assertThat(Fingerprints.structured("REQ-1", 42L, 1700000000L))
                .isEqualTo(Fingerprints.structured("REQ-1", 42L, 1700000000L));
    }

    @Test
    void textContainingDelimiterDoesNotCollideWithFieldBoundary() {
        // "a|b" 单字段不得与 "a","b" 两字段同指纹；分隔符 ':' 同理
        String oneField = Fingerprints.structured("a|b");
        String twoFields = Fingerprints.structured("a", "b");
        assertThat(oneField).isNotEqualTo(twoFields);

        String colon = Fingerprints.structured("s:3:abc");
        String other = Fingerprints.structured("s", "3", "abc");
        assertThat(colon).isNotEqualTo(other);
    }

    @Test
    void differentFieldValueProducesDifferentFingerprint() {
        assertThat(Fingerprints.structured("REQ-1", 1L, 100L))
                .isNotEqualTo(Fingerprints.structured("REQ-1", 1L, 101L));
        assertThat(Fingerprints.structured("REQ-1", 1L, 100L))
                .isNotEqualTo(Fingerprints.structured("REQ-2", 1L, 100L));
    }

    @Test
    void collectionsAreStructurallyDistinguished() {
        String listOne = Fingerprints.structured("REQ", List.of("a|b"));
        String listTwo = Fingerprints.structured("REQ", List.of("a", "b"));
        assertThat(listOne).isNotEqualTo(listTwo);
        String flat = Fingerprints.structured("REQ", "a|b");
        assertThat(flat).isNotEqualTo(listOne);
    }
}
