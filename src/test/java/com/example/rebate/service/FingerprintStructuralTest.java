package com.example.rebate.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.example.rebate.support.FingerprintService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 幂等指纹必须结构化无歧义：字段顺序无关，但含分隔符 {@code |} 或 JSON 结构字符的合法文本
 * 不得与结构不同的另一组参数碰撞。
 */
class FingerprintStructuralTest {

    private final FingerprintService fingerprints = new FingerprintService(new ObjectMapper());

    @Test
    void keyOrderDoesNotChangeFingerprint() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("reason", "事后核查");
        a.put("amountCents", 5000);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("amountCents", 5000);
        b.put("reason", "事后核查");
        assertEquals(fingerprints.fingerprint("OP", "k", a), fingerprints.fingerprint("OP", "k", b));
    }

    @Test
    void textContainingPipeDelimiterIsStillDistinguished() {
        // 朴素的 "字段拼接" 实现下两组参数会退化为相同串；结构化 JSON 指纹必须区分。
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("reason", "x|y");
        a.put("correctionNo", "C1");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("reason", "x");
        b.put("correctionNo", "y|C1");
        assertNotEquals(fingerprints.fingerprint("OP", "k", a),
                fingerprints.fingerprint("OP", "k", b));
    }

    @Test
    void textContainingJsonStructuralCharsIsStillDistinguished() {
        Map<String, Object> benign = new LinkedHashMap<>();
        benign.put("reason", "normal");
        benign.put("amountCents", 100);
        Map<String, Object> injected = new LinkedHashMap<>();
        injected.put("reason", "normal\",\"amountCents\":999,\"x\":\"");
        injected.put("amountCents", 100);
        assertNotEquals(fingerprints.fingerprint("OP", "k", benign),
                fingerprints.fingerprint("OP", "k", injected));
    }

    @Test
    void differentInstantOrVersionChangesFingerprint() {
        Map<String, Object> v1 = new LinkedHashMap<>();
        v1.put("expectedVersion", 2);
        v1.put("occurredAt", "2026-03-01T00:00:00Z");
        Map<String, Object> v2 = new LinkedHashMap<>();
        v2.put("expectedVersion", 3);
        v2.put("occurredAt", "2026-03-01T00:00:00Z");
        Map<String, Object> t2 = new LinkedHashMap<>();
        t2.put("expectedVersion", 2);
        t2.put("occurredAt", "2026-03-01T00:00:01Z");
        String base = fingerprints.fingerprint("OP", "k", v1);
        assertNotEquals(base, fingerprints.fingerprint("OP", "k", v2));
        assertNotEquals(base, fingerprints.fingerprint("OP", "k", t2));
    }
}
