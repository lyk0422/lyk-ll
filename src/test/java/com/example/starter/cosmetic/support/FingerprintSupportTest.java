package com.example.starter.cosmetic.support;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等指纹结构化无歧义测试：合法文本含分隔符时，不同结构不得产生相同指纹。
 */
class FingerprintSupportTest {

    @Test
    void separatorInsideTextDoesNotCollideWithStructuredBoundary() {
        // 两个字段 "a|b" / "c" 与 "a" / "b|c"：若简单拼接分隔符会撞车，结构化指纹必须区分
        Map<String, Object> left = new LinkedHashMap<>();
        left.put("requestId", "REQ-1");
        left.put("labels", List.of("适用于0-36个月|含香精", "第二段"));
        Map<String, Object> right = new LinkedHashMap<>();
        right.put("requestId", "REQ-1");
        right.put("labels", List.of("适用于0-36个月", "|含香精;第二段"));

        assertThat(FingerprintSupport.sha256(left)).isNotEqualTo(FingerprintSupport.sha256(right));
    }

    @Test
    void listSplitBoundaryIsSignificant() {
        // 单个元素 "a,b" 与两个元素 "a","b" 不得同指纹
        Map<String, Object> one = Map.of("requestId", "REQ-2", "claims", List.of("a,b"));
        Map<String, Object> two = Map.of("requestId", "REQ-2", "claims", List.of("a", "b"));

        assertThat(FingerprintSupport.sha256(one)).isNotEqualTo(FingerprintSupport.sha256(two));
    }

    @Test
    void nullAndEmptyStringAreDistinguished() {
        Map<String, Object> withNull = new LinkedHashMap<>();
        withNull.put("requestId", "REQ-3");
        withNull.put("packagingText", null);
        Map<String, Object> withEmpty = new LinkedHashMap<>();
        withEmpty.put("requestId", "REQ-3");
        withEmpty.put("packagingText", "");

        assertThat(FingerprintSupport.sha256(withNull)).isNotEqualTo(FingerprintSupport.sha256(withEmpty));
    }

    @Test
    void sameStructureIsDeterministicRegardlessOfMapOrder() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("requestId", "REQ-4");
        a.put("productCode", "P-1");
        a.put("claims", List.of("MOISTURIZING"));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("claims", List.of("MOISTURIZING"));
        b.put("productCode", "P-1");
        b.put("requestId", "REQ-4");

        assertThat(FingerprintSupport.sha256(a)).isEqualTo(FingerprintSupport.sha256(b));
    }
}
