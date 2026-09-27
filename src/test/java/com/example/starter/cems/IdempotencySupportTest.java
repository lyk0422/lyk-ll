package com.example.starter.cems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等指纹结构化单元测试：指纹为 JSON 规范化后的 SHA-256，
 * 合法文本含分隔符（|、=、,）也按 JSON 字符串边界区分，无拼接歧义。
 */
class IdempotencySupportTest {

    private final IdempotencySupport idem = new IdempotencySupport(new ObjectMapper());

    @Test
    void fingerprintIsDeterministicForSameParams() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("outletCode", "OUT-01");
        fields.put("hourUtc", "2026-09-26T08:00:00Z");
        assertEquals(idem.fingerprint("r1", fields), idem.fingerprint("r1", fields));
    }

    @Test
    void separatorInsideLegalTextDoesNotCollideWithFieldBoundaries() {
        // 旧式拼接 "requestId=r|{a=1, b=2}" 会与单字段值 "1, b=2" 撞串；结构化 JSON 必须区分
        Map<String, Object> twoFields = new LinkedHashMap<>();
        twoFields.put("a", "1");
        twoFields.put("b", "2");
        Map<String, Object> oneFieldWithSeparator = new LinkedHashMap<>();
        oneFieldWithSeparator.put("a", "1, b=2");
        assertNotEquals(idem.fingerprint("r", twoFields),
                idem.fingerprint("r", oneFieldWithSeparator),
                "含分隔符的合法文本必须与字段边界区分");

        Map<String, Object> pipeValue = new LinkedHashMap<>();
        pipeValue.put("a", "x|y");
        Map<String, Object> plainValue = new LinkedHashMap<>();
        plainValue.put("a", "xy");
        assertNotEquals(idem.fingerprint("r", pipeValue), idem.fingerprint("r", plainValue));
    }

    @Test
    void requestIdIsPartOfFingerprint() {
        Map<String, Object> fields = Map.of("a", "1");
        assertNotEquals(idem.fingerprint("r1", fields), idem.fingerprint("r2", fields));
    }
}
