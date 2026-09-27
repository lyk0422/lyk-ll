package com.example.starter.idem;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 结构化指纹纯单元测试：长度前缀编码保证合法文本含分隔符也无歧义，
 * 覆盖字段数、顺序、空值与同参稳定性。
 */
class IdempotencyFingerprintTest {

    @Test
    void delimiterInsideValueCannotCollapseFields() {
        // 朴素 '|'.join 下两组序列完全相同；长度前缀编码必须区分
        String a = IdempotencyService.structuredFingerprint("EP", List.of("a|b", "c"));
        String b = IdempotencyService.structuredFingerprint("EP", List.of("a", "b|c"));
        assertNotEquals(a, b);
    }

    @Test
    void fieldCountAndOrderParticipate() {
        assertNotEquals(
                IdempotencyService.structuredFingerprint("EP", List.of("x", "y")),
                IdempotencyService.structuredFingerprint("EP", List.of("xy")));
        assertNotEquals(
                IdempotencyService.structuredFingerprint("EP", List.of("x", "y")),
                IdempotencyService.structuredFingerprint("EP", List.of("y", "x")));
    }

    @Test
    void nullAndEmptyAreDistinctAndStable() {
        String withNull = IdempotencyService.structuredFingerprint("EP", Arrays.asList((String) null));
        String withEmpty = IdempotencyService.structuredFingerprint("EP", List.of(""));
        assertNotEquals(withNull, withEmpty);
        assertEquals(withNull, IdempotencyService.structuredFingerprint("EP", Arrays.asList((String) null)));
    }

    @Test
    void endpointAndRequestIdIsolateNamespaces() {
        String fp = IdempotencyService.structuredFingerprint("EP-A", List.of("r1", "v1"));
        assertNotEquals(fp, IdempotencyService.structuredFingerprint("EP-B", List.of("r1", "v1")));
    }
}
