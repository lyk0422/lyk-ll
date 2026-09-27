package com.example.starter.cutover.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 幂等指纹结构化无歧义测试：合法文本含分隔符也须区分。
 */
class FingerprintUtilTest {

    @Test
    @DisplayName("同参指纹稳定一致")
    void samePartsSameFingerprint() {
        String a = FingerprintUtil.sha256("BATCH_PUBLISH", "B-1", "REQ-A", 1);
        String b = FingerprintUtil.sha256("BATCH_PUBLISH", "B-1", "REQ-A", 1);
        assertThat(a).isEqualTo(b);
        assertThat(a).hasSize(64);
    }

    @Test
    @DisplayName("结构化指纹：字段边界移动（朴素拼接碰撞对）必须区分")
    void separatorShiftsAreDistinguished() {
        // 朴素拼接 "A"+"B1" 与 "AB"+"1" 会碰撞，结构化 JSON 数组编码必须区分
        assertThat(FingerprintUtil.sha256("A", "B1"))
                .isNotEqualTo(FingerprintUtil.sha256("AB", "1"));
        // 文本本身含分隔符/引号/逗号也不产生歧义
        assertThat(FingerprintUtil.sha256("REQ,1", "X"))
                .isNotEqualTo(FingerprintUtil.sha256("REQ", "1,X"));
        assertThat(FingerprintUtil.sha256("a\"b", "c"))
                .isNotEqualTo(FingerprintUtil.sha256("a", "b\"c"));
        // null 与字符串 "null" 区分
        assertThat(FingerprintUtil.sha256("K", (Object) null))
                .isNotEqualTo(FingerprintUtil.sha256("K", "null"));
        // 数字 1 与字符串 "1" 区分
        assertThat(FingerprintUtil.sha256("K", 1))
                .isNotEqualTo(FingerprintUtil.sha256("K", "1"));
    }
}
