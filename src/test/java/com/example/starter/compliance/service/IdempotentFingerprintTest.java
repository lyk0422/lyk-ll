package com.example.starter.compliance.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.compliance.support.JsonSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 幂等指纹结构化无歧义单元测试：合法文本包含分隔符（逗号、竖线等）时，
 * 不得与多值集合或字段拼接产生相同指纹；字段顺序不影响指纹。
 */
class IdempotentFingerprintTest {

    @Test
    void structuredFingerprintDistinguishesDelimitersInsideText() {
        // 单个含逗号/竖线的值，与拆成两个值必须可区分。
        String singleContainingDelimiter = canonicalParams(Map.of(
                "values", List.of("a,b|c"),
                "version", 1));
        String twoValues = canonicalParams(Map.of(
                "values", List.of("a", "b|c"),
                "version", 1));
        assertThat(singleContainingDelimiter).isNotEqualTo(twoValues);

        // 相邻字段直接拼接也不会混淆边界。
        String splitBetweenFieldsA = canonicalParams(Map.of(
                "first", "ab", "second", "c", "version", 1));
        String splitBetweenFieldsB = canonicalParams(Map.of(
                "first", "a", "second", "bc", "version", 1));
        assertThat(splitBetweenFieldsA).isNotEqualTo(splitBetweenFieldsB);
    }

    @Test
    void fingerprintIgnoresFieldInsertionOrderButDistinguishesContent() {
        String orderedOne = canonicalParams(new LinkedHashMap<>(Map.of(
                "lineCode", "L-1", "occupiedVersion", 2, "batchNo", "B")));
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("batchNo", "B");
        reordered.put("occupiedVersion", 2);
        reordered.put("lineCode", "L-1");
        String orderedTwo = canonicalParams(reordered);
        assertThat(orderedOne).isEqualTo(orderedTwo);

        String changedVersion = canonicalParams(new LinkedHashMap<>(Map.of(
                "lineCode", "L-1", "occupiedVersion", 3, "batchNo", "B")));
        assertThat(orderedOne).isNotEqualTo(changedVersion);
    }

    @Test
    void sameKeySameParamsProduceSameFingerprint() {
        Map<String, Object> params = Map.of("lineCode", "L-1", "allergens",
                List.of("ALG_MILK", "ALG_EGG"));
        assertThat(IdempotentExecutor.fingerprint("req-1", params))
                .isEqualTo(IdempotentExecutor.fingerprint("req-1",
                        new LinkedHashMap<>(params)));
        assertThat(IdempotentExecutor.fingerprint("req-1", params))
                .isNotEqualTo(IdempotentExecutor.fingerprint("req-2", params));
    }

    private static String canonicalParams(Map<String, Object> params) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("requestId", "req");
        material.put("params", params);
        return JsonSupport.canonical(material);
    }
}
