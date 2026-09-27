package com.example.starter.idempotency;

import com.example.starter.dto.CustomsBindRequest;
import com.example.starter.dto.CustomsRegisterRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 结构化指纹编码测试：长度前缀编码保证字段边界无歧义，
 * 合法文本含 "|"、","、":" 等分隔符时不同拆分仍产生不同指纹。
 */
class StructuredFingerprintTest {

    @Test
    void separatorInsideTextDoesNotCollideWithFieldBoundary() {
        // 经典拼接歧义：("a|b", "c") 与 ("a", "b|c") 在 "|" 拼接下编码相同。
        String left = Fingerprints.structured(
                Fingerprints.field("a|b"), Fingerprints.field("c"));
        String right = Fingerprints.structured(
                Fingerprints.field("a"), Fingerprints.field("b|c"));
        assertNotEquals(left, right, "含分隔符文本的不同字段拆分必须产生不同编码");
    }

    @Test
    void commaInsideCollectionElementDoesNotCollide() {
        // 集合元素含逗号：["a,b", "c"] 与 ["a", "b,c"] 必须可区分。
        String left = Fingerprints.collection(List.of("a,b", "c"));
        String right = Fingerprints.collection(List.of("a", "b,c"));
        assertNotEquals(left, right);
    }

    @Test
    void emptyCollectionDiffersFromCollectionWithEmptyString() {
        assertNotEquals(Fingerprints.collection(List.of()),
                Fingerprints.collection(List.of("")));
    }

    @Test
    void nullFieldDiffersFromEmptyAndMissing() {
        assertNotEquals(Fingerprints.field(null), Fingerprints.field(""));
        assertEquals("N", Fingerprints.field(null));
    }

    @Test
    void lengthPrefixUsesUtf8ByteLength() {
        // 多字节字符按 UTF-8 字节长度编码，"中" 为 3 字节。
        assertEquals("V3:中", Fingerprints.field("中"));
        assertEquals("V1:a", Fingerprints.field("a"));
    }

    @Test
    void customsFingerprintsDistinguishSeparatorText() {
        Instant at = Instant.parse("2026-09-07T02:00:00Z");
        LocalDate validUntil = LocalDate.of(2026, 10, 31);
        // 企业名含分隔符的不同拆分：("厂|苗") vs 不同字段组合。
        CustomsRegisterRequest a = new CustomsRegisterRequest(
                "r1", "CD-1", "甲|乙", "丙", validUntil, 100);
        CustomsRegisterRequest b = new CustomsRegisterRequest(
                "r1", "CD-1", "甲", "乙|丙", validUntil, 100);
        assertNotEquals(Fingerprints.customsRegister(a), Fingerprints.customsRegister(b));

        CustomsBindRequest c = new CustomsBindRequest("r2", "CD|1", "B-1", 10, 1, at);
        CustomsBindRequest d = new CustomsBindRequest("r2", "CD", "1|B-1", 10, 1, at);
        assertNotEquals(Fingerprints.customsBind(c), Fingerprints.customsBind(d));
    }

    @Test
    void customsFingerprintStableForSameParameters() {
        Instant at = Instant.parse("2026-09-07T02:00:00Z");
        CustomsBindRequest request = new CustomsBindRequest("r3", "CD-1", "B-1", 10, 1, at);
        assertEquals(Fingerprints.customsBind(request), Fingerprints.customsBind(request));
    }
}
