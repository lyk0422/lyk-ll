package com.example.starter.idempotent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 幂等指纹规范：结构化长度分帧无歧义、文本含分隔符可区分、数值按规范值等价。
 */
class FingerprintUtilTest {

    private final FingerprintUtil fingerprintUtil = new FingerprintUtil(new ObjectMapper());

    public record Payload(String stationCode, String requestId, BigDecimal amount, Integer count) {
    }

    @Test
    void decimalTextEquivalenceProducesSameFingerprint() {
        Payload a = new Payload("ST01", "REQ-1", new BigDecimal("20.000"), 3);
        Payload b = new Payload("ST01", "REQ-1", new BigDecimal("20.0"), 3);
        Payload c = new Payload("ST01", "REQ-1", new BigDecimal("20"), 3);
        assertEquals(fingerprintUtil.fingerprint("K1", a), fingerprintUtil.fingerprint("K1", b));
        assertEquals(fingerprintUtil.fingerprint("K1", a), fingerprintUtil.fingerprint("K1", c));
    }

    @Test
    void differentNumericValuesProduceDifferentFingerprint() {
        Payload a = new Payload("ST01", "REQ-1", new BigDecimal("20.000"), 3);
        Payload b = new Payload("ST01", "REQ-1", new BigDecimal("20.001"), 3);
        assertNotEquals(fingerprintUtil.fingerprint("K1", a), fingerprintUtil.fingerprint("K1", b));
    }

    @Test
    void textContainingDelimitersCannotCollideFields() {
        // 第一种：编码含分隔符，把第二字段“撑”进看似第三字段；长度分帧后必须仍可区分
        Payload injected = new Payload("ST01", "X|5:hello", new BigDecimal("1"), 1);
        Payload plain = new Payload("ST01", "X", new BigDecimal("1"), 1);
        assertNotEquals(fingerprintUtil.fingerprint("K1", injected),
                fingerprintUtil.fingerprint("K1", plain));
    }

    @Test
    void concatenationAmbiguityIsResolved() {
        // ("ab","c") 与 ("a","bc") 若用裸拼接会碰撞，长度分帧必须区分
        Payload abC = new Payload("ab", "c", BigDecimal.ONE, 1);
        Payload aBc = new Payload("a", "bc", BigDecimal.ONE, 1);
        assertNotEquals(fingerprintUtil.fingerprint("K1", abC),
                fingerprintUtil.fingerprint("K1", aBc));
    }

    @Test
    void fieldOrderDoesNotMatterButStructureDoes() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String order1 = "{\"stationCode\":\"ST01\",\"amount\":20}";
        String order2 = "{\"amount\":20,\"stationCode\":\"ST01\"}";
        String extraField = "{\"stationCode\":\"ST01\",\"amount\":20,\"extra\":1}";
        String f1 = fingerprintUtil.fingerprint("K1", mapper.readTree(order1));
        String f2 = fingerprintUtil.fingerprint("K1", mapper.readTree(order2));
        String f3 = fingerprintUtil.fingerprint("K1", mapper.readTree(extraField));
        assertEquals(f1, f2, "同一结构字段顺序不同应同指纹");
        assertNotEquals(f1, f3, "多出字段必须不同指纹");
    }

    @Test
    void requestIdIsPartOfFingerprint() {
        Payload payload = new Payload("ST01", "REQ-1", new BigDecimal("20.000"), 3);
        assertNotEquals(fingerprintUtil.fingerprint("K1", payload),
                fingerprintUtil.fingerprint("K2", payload));
    }

    @Test
    void zeroScalesAreEquivalent() {
        Payload a = new Payload("ST01", "REQ-1", new BigDecimal("0.000"), 0);
        Payload b = new Payload("ST01", "REQ-1", new BigDecimal("0"), 0);
        assertEquals(fingerprintUtil.fingerprint("K1", a), fingerprintUtil.fingerprint("K1", b));
    }
}
