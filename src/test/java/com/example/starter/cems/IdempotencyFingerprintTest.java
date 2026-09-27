package com.example.starter.cems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 结构化指纹单元测试：指纹须结构化无歧义，合法文本含分隔符（|、=、,、{}）也须区分；
 * 集合按序、版本与精确时刻均参与指纹。
 */
class IdempotencyFingerprintTest {

    private final IdempotencySupport idem = new IdempotencySupport(new ObjectMapper(), null, null);

    @Test
    void structuredFingerprintDistinguishesTextContainingSeparators() {
        // 旧 toString 规范化下 {a="1, b=x"} 与 {a="1", b="x"} 都会拼成 "{a=1, b=x}" 而碰撞；
        // 结构化 JSON 指纹必须区分。
        Map<String, Object> single = new LinkedHashMap<>();
        single.put("a", "1, b=x");
        Map<String, Object> two = new LinkedHashMap<>();
        two.put("a", "1");
        two.put("b", "x");
        assertEquals(idem.fingerprint("r1", single), idem.fingerprint("r1", two),
                "旧规范化指纹对该用例存在碰撞（仅作对照，旧入口行为不变）");
        assertNotEquals(idem.structuredFingerprint("r1", single), idem.structuredFingerprint("r1", two),
                "结构化指纹须区分含分隔符的合法文本");

        Map<String, Object> pipe = new LinkedHashMap<>();
        pipe.put("reason", "a|b");
        Map<String, Object> split = new LinkedHashMap<>();
        split.put("reason", "a");
        split.put("b", "b");
        assertNotEquals(idem.structuredFingerprint("r1", pipe), idem.structuredFingerprint("r1", split));
    }

    @Test
    void structuredFingerprintCoversVersionCollectionAndExactMoments() {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("outletCode", "OV-01");
        base.put("expectedVersion", 1);
        base.put("validFrom", "2026-09-26T06:00:00");
        base.put("entries", List.of(
                Map.of("hour", "2026-09-26T07:00", "rainfall", new BigDecimal("5.0"), "overflowFlag", true),
                Map.of("hour", "2026-09-26T08:00", "overflowFlag", false)));

        Map<String, Object> same = new LinkedHashMap<>(base);
        assertEquals(idem.structuredFingerprint("r1", base), idem.structuredFingerprint("r1", same),
                "同参指纹必须一致");

        // 版本不同 → 指纹不同
        Map<String, Object> otherVersion = new LinkedHashMap<>(base);
        otherVersion.put("expectedVersion", 2);
        assertNotEquals(idem.structuredFingerprint("r1", base), idem.structuredFingerprint("r1", otherVersion));

        // 集合顺序不同 → 指纹不同
        Map<String, Object> reordered = new LinkedHashMap<>(base);
        reordered.put("entries", List.of(
                Map.of("hour", "2026-09-26T08:00", "overflowFlag", false),
                Map.of("hour", "2026-09-26T07:00", "rainfall", new BigDecimal("5.0"), "overflowFlag", true)));
        assertNotEquals(idem.structuredFingerprint("r1", base), idem.structuredFingerprint("r1", reordered));

        // 精确时刻不同 → 指纹不同
        Map<String, Object> otherMoment = new LinkedHashMap<>(base);
        otherMoment.put("validFrom", "2026-09-26T06:00:01");
        assertNotEquals(idem.structuredFingerprint("r1", base), idem.structuredFingerprint("r1", otherMoment));

        // requestId 不同 → 指纹不同
        assertNotEquals(idem.structuredFingerprint("r1", base), idem.structuredFingerprint("r2", base));
    }
}
