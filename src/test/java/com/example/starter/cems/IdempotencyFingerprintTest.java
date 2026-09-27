package com.example.starter.cems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 结构化幂等指纹纯单元测试：长度前缀编码保证字段边界无歧义，
 * 合法文本即便包含分隔符、空串或跨行也必须产生不同指纹。
 */
class IdempotencyFingerprintTest {

    private final IdempotencySupport idem = new IdempotencySupport(new ObjectMapper());

    private Map<String, Object> fields(String name, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(name, value);
        return m;
    }

    @Test
    void sameStructuredInputProducesSameFingerprint() {
        Map<String, Object> a = fields("reason", "调差|含分隔符;");
        Map<String, Object> b = fields("reason", "调差|含分隔符;");
        assertEquals(idem.fingerprintStructured("rid-1", a), idem.fingerprintStructured("rid-1", b));
    }

    @Test
    void delimiterInsideValueCannotShiftFieldBoundary() {
        // 两组语义不同的输入，在朴素拼接下可能撞串，长度前缀编码必须区分。
        String fpA = idem.fingerprintStructured("r", fields("a", "1;b=2"));
        Map<String, Object> two = new LinkedHashMap<>();
        two.put("a", "1");
        two.put("b", "2");
        String fpB = idem.fingerprintStructured("r", two);
        assertNotEquals(fpA, fpB, "值中包含分隔符不得伪造出额外字段");
    }

    @Test
    void emptyStringDiffersFromNullAndFromAbsentField() {
        String empty = idem.fingerprintStructured("r", fields("a", ""));
        String nullVal = idem.fingerprintStructured("r", fields("a", null));
        String absent = idem.fingerprintStructured("r", new LinkedHashMap<>());
        assertNotEquals(empty, nullVal, "空串与 null 必须可区分");
        assertNotEquals(empty, absent, "空串字段与缺字段必须可区分");
        assertNotEquals(nullVal, absent);
    }

    @Test
    void fieldNameAndValueSplitsAreUnambiguous() {
        // ("ab","c") vs ("a","bc") 长度前缀下不同。
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("ab", "c");
        Map<String, Object> y = new LinkedHashMap<>();
        y.put("a", "bc");
        assertNotEquals(idem.fingerprintStructured("r", x), idem.fingerprintStructured("r", y));
    }

    @Test
    void distinctRequestIdsAlwaysDifferEvenWithSameFields() {
        Map<String, Object> m = fields("k", "v");
        assertNotEquals(idem.fingerprintStructured("rid-1", m), idem.fingerprintStructured("rid-2", m));
    }

    @Test
    void collectionsAreCanonicallyEncodedAndUnambiguous() {
        Map<String, Object> asList = fields("items", List.of("a", "b"));
        // 朴素 toString 下 ["a", "b"] 与单值 "[a, b]" 可能撞串，递归带计数编码必须区分。
        Map<String, Object> asSingle = fields("items", "[a, b]");
        assertNotEquals(idem.fingerprintStructured("r", asList), idem.fingerprintStructured("r", asSingle));

        Map<String, Object> twoItems = fields("items", List.of("1", "2"));
        Map<String, Object> oneItem = fields("items", List.of("1,2"));
        assertNotEquals(idem.fingerprintStructured("r", twoItems), idem.fingerprintStructured("r", oneItem));
    }
}
