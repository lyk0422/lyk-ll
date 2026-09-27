package com.example.starter.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 指纹结构化无歧义：requestId/操作类型与业务字段显式分隔，
 * 合法文本含分隔符（含 NUL、竖线、JSON 结构符）也不会与字段边界混淆。
 */
class FingerprintsTest {

    @Test
    @DisplayName("字段边界无歧义：拼接可混淆的文本组合产生不同指纹")
    void fieldBoundariesUnambiguous() {
        // 若简单拼接，"ab"+"c" 与 "a"+"bc" 不可区分；显式分隔后必须不同
        String h1 = Fingerprints.sha256("ab", "c", "{}".getBytes(StandardCharsets.UTF_8));
        String h2 = Fingerprints.sha256("a", "bc", "{}".getBytes(StandardCharsets.UTF_8));
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    @DisplayName("业务 JSON 结构化：文本含分隔符/结构符的不同内容指纹不同，同内容指纹稳定")
    void structuredJsonDistinguishesContent() {
        // 两个包内容：pointCode 含竖线与引号，朴素拼接可能撞车，JSON 结构化后必不同
        byte[] a = "{\"seq\":1,\"pointCode\":\"P|1\",\"measuredTorque\":10}".getBytes(StandardCharsets.UTF_8);
        byte[] b = "{\"seq\":1,\"pointCode\":\"P\",\"measuredTorque\":110}".getBytes(StandardCharsets.UTF_8);
        String ha = Fingerprints.sha256("req", "OP", a);
        String hb = Fingerprints.sha256("req", "OP", b);
        assertThat(ha).isNotEqualTo(hb);

        // 同内容稳定
        String ha2 = Fingerprints.sha256("req", "OP", a);
        assertThat(ha2).isEqualTo(ha);
        // 指纹为 64 位十六进制
        assertThat(ha).matches("[0-9a-f]{64}");
    }
}
