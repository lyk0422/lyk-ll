package com.example.starter.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 指纹结构化无歧义验证：合法文本即使包含分隔符（逗号/冒号/花括号），
 * 结构化 JSON 序列化后仍与“换一种切分”的参数明确区分，朴素字符串拼接则会碰撞。
 */
class FingerprintsTest {

    /** 仅用于测试的两字段业务参数记录。 */
    public record TwoFields(String a, String b) {
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String fingerprint(Object body) throws Exception {
        return Fingerprints.sha256("req-1", "OP", objectMapper.writeValueAsBytes(body));
    }

    @Test
    @DisplayName("含分隔符的合法文本：两种字段切分指纹不同；同参重放指纹相同")
    void structuredFingerprintDistinguishesDelimiterText() throws Exception {
        // 朴素拼接 a+b：两种切分都得到同一字符串 "x,y"，会碰撞；
        // 结构化 JSON 明确保留字段边界，不会碰撞。
        TwoFields splitOne = new TwoFields("x,", "y");
        TwoFields splitTwo = new TwoFields("x", ",y");
        assertThat(splitOne.a() + splitOne.b()).isEqualTo(splitTwo.a() + splitTwo.b());

        String fpOne = fingerprint(splitOne);
        String fpTwo = fingerprint(splitTwo);
        assertThat(fpOne).isNotEqualTo(fpTwo);

        // 同参（含分隔符）重放指纹一致。
        assertThat(fingerprint(new TwoFields("x,", "y"))).isEqualTo(fpOne);

        // requestId 与操作类型也参与指纹：换键即不同指纹。
        byte[] json = objectMapper.writeValueAsBytes(splitOne);
        assertThat(Fingerprints.sha256("req-OTHER", "OP", json)).isNotEqualTo(fpOne);
        assertThat(Fingerprints.sha256("req-1", "OTHER_OP", json)).isNotEqualTo(fpOne);

        // 集合顺序也结构化保留：[1,2] 与 [2,1] 不同。
        String fpListOne = fingerprint(List.of(1, 2));
        String fpListTwo = fingerprint(List.of(2, 1));
        assertThat(fpListOne).isNotEqualTo(fpListTwo);
    }
}
