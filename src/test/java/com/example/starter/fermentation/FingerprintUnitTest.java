package com.example.starter.fermentation;

import com.example.starter.fermentation.service.IdempotencyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 请求指纹结构化无歧义单元测试：业务字段经 JSON 数组序列化后再入指纹，
 * 含分隔符 '|' 的合法文本不得与多字段拼接发生碰撞。
 */
class FingerprintUnitTest {

    private final IdempotencyService idempotencyService = new IdempotencyService(null, null);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("单字段含分隔符与两字段拼接的指纹必须不同")
    void separatorInsideValueDoesNotCollideWithFieldBoundary() throws Exception {
        // 未结构化的朴素拼接会碰撞："a|b" 与 ["a","b"] 经 '|' 拼接相同
        String singleField = objectMapper.writeValueAsString(List.of("a|b"));
        String twoFields = objectMapper.writeValueAsString(List.of("a", "b"));
        assertNotEquals(singleField, twoFields, "JSON 结构化编码应区分字段边界");

        String fpSingle = idempotencyService.fingerprint("POST /x", "req-1", singleField);
        String fpTwo = idempotencyService.fingerprint("POST /x", "req-1", twoFields);
        assertNotEquals(fpSingle, fpTwo, "含分隔符的合法文本不得与字段边界碰撞");

        // 其它含分隔符、引号等合法文本组合同样可区分
        String edgeA = objectMapper.writeValueAsString(List.of("x|y", "z"));
        String edgeB = objectMapper.writeValueAsString(List.of("x", "y|z"));
        assertNotEquals(
                idempotencyService.fingerprint("POST /x", "req-1", edgeA),
                idempotencyService.fingerprint("POST /x", "req-1", edgeB),
                "相邻字段间挪动分隔符不得产生相同指纹");
    }
}
