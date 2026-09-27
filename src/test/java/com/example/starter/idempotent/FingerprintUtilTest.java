package com.example.starter.idempotent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 幂等指纹单元测试：结构化无歧义、数值等价归一、版本/集合/精确时刻全覆盖。
 */
class FingerprintUtilTest {

    private final FingerprintUtil util = new FingerprintUtil(
            new ObjectMapper().registerModule(new JavaTimeModule()));

    private record SamplePayload(String text, BigDecimal amount, Long version,
                                 List<String> operators, LocalDateTime at) {
    }

    private SamplePayload payload(String text, String amount) {
        return new SamplePayload(text, new BigDecimal(amount), 1L,
                List.of("OP-A", "OP-B"), LocalDateTime.of(2026, 9, 26, 8, 0));
    }

    @Test
    void samePayloadIsDeterministic() {
        assertEquals(util.fingerprint("REQ-1", payload("hello", "10.5")),
                util.fingerprint("REQ-1", payload("hello", "10.5")));
    }

    @Test
    void numericTextEquivalenceNormalized() {
        // 整数或小数文本数值等价按规范值比较：1 / 1.0 / 1.00 指纹相同
        String base = util.fingerprint("REQ-1", payload("hello", "1"));
        assertEquals(base, util.fingerprint("REQ-1", payload("hello", "1.0")));
        assertEquals(base, util.fingerprint("REQ-1", payload("hello", "1.00")));
        assertEquals(base, util.fingerprint("REQ-1", payload("hello", "1.000")));
        // 数值不同则指纹不同
        assertNotEquals(base, util.fingerprint("REQ-1", payload("hello", "1.001")));
    }

    @Test
    void separatorInTextStillDistinguished() {
        // 合法文本含分隔符也须区分：requestId 与报文边界、字段边界不得混淆
        String withBarInKey = util.fingerprint("a|b", payload("c", "1"));
        String shiftedToText = util.fingerprint("a", payload("b|c", "1"));
        assertNotEquals(withBarInKey, shiftedToText);

        SamplePayload p1 = new SamplePayload("u|v", BigDecimal.ONE, 1L, List.of("A"), null);
        SamplePayload p2 = new SamplePayload("u", BigDecimal.ONE, 1L, List.of("v"), null);
        assertNotEquals(util.fingerprint("r", p1), util.fingerprint("r", p2),
                "分隔符在文本内移动字段边界须产生不同指纹");
    }

    @Test
    void collectionOrderAndContentCovered() {
        SamplePayload ab = new SamplePayload("t", BigDecimal.ONE, 1L, List.of("A", "B"), null);
        SamplePayload ba = new SamplePayload("t", BigDecimal.ONE, 1L, List.of("B", "A"), null);
        assertNotEquals(util.fingerprint("r", ab), util.fingerprint("r", ba),
                "集合顺序不同须产生不同指纹");
        SamplePayload abc = new SamplePayload("t", BigDecimal.ONE, 1L, List.of("A", "B", "C"), null);
        assertNotEquals(util.fingerprint("r", ab), util.fingerprint("r", abc));
    }

    @Test
    void versionAndExactTimestampCovered() {
        SamplePayload v1 = new SamplePayload("t", BigDecimal.ONE, 1L, List.of("A"),
                LocalDateTime.of(2026, 9, 26, 8, 0));
        SamplePayload v2 = new SamplePayload("t", BigDecimal.ONE, 2L, List.of("A"),
                LocalDateTime.of(2026, 9, 26, 8, 0));
        assertNotEquals(util.fingerprint("r", v1), util.fingerprint("r", v2),
                "版本不同须产生不同指纹");
        SamplePayload t2 = new SamplePayload("t", BigDecimal.ONE, 1L, List.of("A"),
                LocalDateTime.of(2026, 9, 26, 8, 0, 1));
        assertNotEquals(util.fingerprint("r", v1), util.fingerprint("r", t2),
                "精确时刻不同须产生不同指纹");
    }
}
