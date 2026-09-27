package com.example.starter.compliance.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.compliance.support.JsonSupport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 幂等指纹结构化无歧义单元测试：
 * 合法文本即使包含逗号、引号等分隔符，也不会与数组/多字段结构发生碰撞。
 */
class IdempotencyFingerprintTest {

    private static String fingerprint(Object params) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("requestId", "REQ-1");
        material.put("params", params);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(JsonSupport.canonical(material).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void delimiterInsideTextNeverCollidesWithStructure() {
        // 单元素文本含逗号 vs 两元素数组：朴素拼接会碰撞，结构化 JSON 必须区分。
        String singleWithComma = fingerprint(Map.of("allergenCodes", List.of("MILK,EGG")));
        String twoElements = fingerprint(Map.of("allergenCodes", List.of("MILK", "EGG")));
        assertThat(singleWithComma).isNotEqualTo(twoElements);

        // 文本中注入引号与 JSON 元字符也不能伪造结构。
        String injected = fingerprint(Map.of("allergenCodes",
                List.of("MILK\",\"x\":[\"EGG")));
        assertThat(injected).isNotEqualTo(twoElements);

        // 字段边界：两个字段各一值 vs 一个字段含分隔文本。
        String twoFields = fingerprint(Map.of(
                "batchNo", "B1", "lineCode", "L1"));
        String oneField = fingerprint(Map.of(
                "batchNo", "B1\",\"lineCode\":\"L1"));
        assertThat(twoFields).isNotEqualTo(oneField);
    }

    @Test
    void sameStructureProducesStableFingerprint() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("lineCode", "L1");
        a.put("allergenCodes", List.of("EGG", "MILK"));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("allergenCodes", List.of("EGG", "MILK"));
        b.put("lineCode", "L1");
        // 字段递归字典序规范化：键顺序不同但结构相同，指纹一致。
        assertThat(fingerprint(a)).isEqualTo(fingerprint(b));
    }
}
