package com.example.starter.survey.support;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 结构化指纹无歧义：合法文本含分隔符（逗号、引号、竖线、换行）也必须区分，
 * 且字段边界（单字段 vs 两字段拼接）不得碰撞。
 */
class FingerprintSeparatorTest {

    @Test
    void delimiterInsideTextDoesNotCollideWithFieldBoundary() {
        // 单字段值内含逗号/竖线，与“两字段用同样字符拼接”结构不同，指纹必须不同。
        String single = Fingerprint.of("r1", "OP", Map.of(
                "a", "x,y",
                "b", "z"));
        String split = Fingerprint.of("r1", "OP", Map.of(
                "a", "x",
                "b", "y,z"));
        assertNotEquals(single, split);

        String pipe = Fingerprint.of("r1", "OP", Map.of("a", "1|2|3", "b", "4"));
        String pipeSplit = Fingerprint.of("r1", "OP", Map.of("a", "1|2", "b", "3|4"));
        assertNotEquals(pipe, pipeSplit);
    }

    @Test
    void quoteAndNewlineInsideValueAreEncoded() {
        String quoted = Fingerprint.of("r1", "OP", Map.of("a", "\"b\":\"x\"", "c", "y"));
        String shifted = Fingerprint.of("r1", "OP", Map.of("a", "\"b", "c", "\"x\",\"y"));
        assertNotEquals(quoted, shifted);

        String newline = Fingerprint.of("r1", "OP", Map.of("a", "line1\nline2", "b", "x"));
        String noNewline = Fingerprint.of("r1", "OP", Map.of("a", "line1", "b", "line2\nx"));
        assertNotEquals(newline, noNewline);
    }

    @Test
    void sameParamsAlwaysSameFingerprint() {
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("a", List.of("1", "2"));
        ordered.put("b", "v");
        String first = Fingerprint.of("req", "OP", ordered);
        String again = Fingerprint.of("req", "OP", ordered);
        assertEquals(first, again);
    }
}
