package com.example.rebate.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.example.rebate.support.FingerprintService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 幂等指纹结构化无歧义：字段顺序无关，但字段边界、集合顺序、版本与精确时刻必须可区分；
 * 合法文本即使含分隔符也不得跨越字段造成碰撞。
 */
class ChargebackFingerprintTest {

    private final FingerprintService fp = new FingerprintService(new ObjectMapper());

    private Map<String, Object> body(String correctionNo, String reason, long amount,
                                     int expectedVersion, String receivedAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("correctionNo", correctionNo);
        m.put("reason", reason);
        m.put("amountCents", amount);
        m.put("expectedVersion", expectedVersion);
        m.put("receivedAt", receivedAt);
        return m;
    }

    @Test
    void delimiterAcrossFieldsMustNotCollide() {
        Map<String, Object> a = Map.of("body", body("C-1|EXTRA", "ab", 100, 1, null));
        Map<String, Object> b = Map.of("body", body("C-1", "a|EXTRAb", 100, 1, null));
        assertNotEquals(fp.fingerprint("OP", "k", a), fp.fingerprint("OP", "k", b),
                "分隔符在字段间移动必须产生不同指纹");
    }

    @Test
    void fieldOrderIsIrrelevantButValuesAreNot() {
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("a", "1");
        ordered.put("b", "2");
        Map<String, Object> reversed = new LinkedHashMap<>();
        reversed.put("b", "2");
        reversed.put("a", "1");
        assertEquals(fp.fingerprint("OP", "k", ordered),
                fp.fingerprint("OP", "k", reversed), "仅字段顺序不同应同指纹");

        assertNotEquals(fp.fingerprint("OP", "k", Map.of("a", "1|2")),
                fp.fingerprint("OP", "k", Map.of("a", "1", "b", "2")),
                "单字段含分隔符与两字段不得同指纹");
    }

    @Test
    void versionInstantCollectionAndOpAndKeyAllDifferentiate() {
        Map<String, Object> base = Map.of("body",
                body("C", "r", 100, 1, "2026-03-01T10:00:00Z"));
        assertNotEquals(fp.fingerprint("OP", "k", base),
                fp.fingerprint("OP", "k", Map.of("body",
                        body("C", "r", 100, 2, "2026-03-01T10:00:00Z"))),
                "expectedVersion 不同应区分");
        assertNotEquals(fp.fingerprint("OP", "k", base),
                fp.fingerprint("OP", "k", Map.of("body",
                        body("C", "r", 100, 1, "2026-03-01T10:00:01Z"))),
                "精确时刻不同应区分");
        assertNotEquals(fp.fingerprint("OP", "k", base),
                fp.fingerprint("OTHER", "k", base), "操作类型不同应区分");
        assertNotEquals(fp.fingerprint("OP", "k1", base),
                fp.fingerprint("OP", "k2", base), "requestId 不同应区分");
        assertNotEquals(fp.fingerprint("OP", "k", Map.of("list", List.of("A", "B"))),
                fp.fingerprint("OP", "k", Map.of("list", List.of("B", "A"))),
                "集合元素顺序（参数序列）应区分");
    }
}
