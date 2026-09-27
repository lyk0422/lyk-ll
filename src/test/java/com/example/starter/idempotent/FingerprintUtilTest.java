package com.example.starter.idempotent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 幂等指纹边界：结构化无歧义（合法文本含分隔符也须区分）、
 * 数值按规范值比较、集合有序、字段顺序无关。
 */
class FingerprintUtilTest {

    private final FingerprintUtil util = new FingerprintUtil(
            new ObjectMapper().registerModule(new JavaTimeModule()));

    @Test
    void separatorInRequestIdAndTextIsDistinguished() {
        Map<String, Object> payload = Map.of("k", "v");
        // requestId 含分隔符：彼此不得相同，也不得与不带分隔符的相同
        assertNotEquals(util.fingerprint("a|b", payload), util.fingerprint("a", payload));
        assertNotEquals(util.fingerprint("a|b", payload), util.fingerprint("a|b|c", payload));
        assertNotEquals(util.fingerprint("2:a", payload), util.fingerprint("a", payload));

        // 业务文本含分隔符：不同切分必须得到不同指纹
        Map<String, Object> text1 = Map.of("op", "OP|01");
        Map<String, Object> text2 = Map.of("op", "OP", "suffix", "01");
        assertNotEquals(util.fingerprint("REQ", text1), util.fingerprint("REQ", text2));
    }

    @Test
    void numericTextEquivalenceUsesCanonicalValue() {
        // 整数与小数文本等价：10、10.0、10.000 指纹相同
        Map<String, Object> asInt = Map.of("qty", new BigDecimal("10"));
        Map<String, Object> asDecimal = Map.of("qty", new BigDecimal("10.0"));
        Map<String, Object> asScaled = Map.of("qty", new BigDecimal("10.000"));
        assertEquals(util.fingerprint("REQ", asInt), util.fingerprint("REQ", asDecimal));
        assertEquals(util.fingerprint("REQ", asInt), util.fingerprint("REQ", asScaled));

        // 不同数值仍不同
        Map<String, Object> other = Map.of("qty", new BigDecimal("10.001"));
        assertNotEquals(util.fingerprint("REQ", asInt), util.fingerprint("REQ", other));
    }

    @Test
    void fieldOrderIrrelevantButCollectionOrderMatters() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("a", 1);
        first.put("b", 2);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("b", 2);
        second.put("a", 1);
        assertEquals(util.fingerprint("REQ", first), util.fingerprint("REQ", second));

        // 集合是有序的：顺序不同则指纹不同
        Map<String, Object> list12 = Map.of("ids", List.of(1, 2));
        Map<String, Object> list21 = Map.of("ids", List.of(2, 1));
        assertNotEquals(util.fingerprint("REQ", list12), util.fingerprint("REQ", list21));
    }
}
