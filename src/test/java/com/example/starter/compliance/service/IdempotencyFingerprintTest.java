package com.example.starter.compliance.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.compliance.web.Requests.ControlledProductionRequest;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * requestId 幂等指纹纯单元测试：规范化结构化 JSON，保证同参稳定、异参可区分；
 * 合法文本即使包含分隔符（逗号/竖线/斜杠/JSON 符号）也不会与另一组参数碰撞。
 */
class IdempotencyFingerprintTest {

    private ControlledProductionRequest request(String requestId, String lineCode, String batch,
            List<String> ignored, int occupiedVersion, long occurredAt) {
        return new ControlledProductionRequest(requestId, lineCode, 7L, batch,
                new BigDecimal("10"), occupiedVersion, occurredAt, null);
    }

    @Test
    void sameParamsProduceSameFingerprint() {
        ControlledProductionRequest a = request("K1", "L-1", "B-1", List.of(), 1, 1000L);
        ControlledProductionRequest b = request("K1", "L-1", "B-1", List.of(), 1, 1000L);
        assertThat(IdempotencySupport.fingerprint("K1", a))
                .isEqualTo(IdempotencySupport.fingerprint("K1", b));
    }

    @Test
    void differentPreciseTimeOrVersionProducesDifferentFingerprint() {
        ControlledProductionRequest base = request("K1", "L-1", "B-1", List.of(), 1, 1000L);
        assertThat(IdempotencySupport.fingerprint("K1", base))
                .isNotEqualTo(IdempotencySupport.fingerprint("K1",
                        request("K1", "L-1", "B-1", List.of(), 1, 1001L)));
        assertThat(IdempotencySupport.fingerprint("K1", base))
                .isNotEqualTo(IdempotencySupport.fingerprint("K1",
                        request("K1", "L-1", "B-1", List.of(), 2, 1000L)));
    }

    @Test
    void differentRequestIdProducesDifferentFingerprintEvenForIdenticalParams() {
        ControlledProductionRequest a = request("K1", "L-1", "B-1", List.of(), 1, 1000L);
        ControlledProductionRequest b = request("K2", "L-1", "B-1", List.of(), 1, 1000L);
        assertThat(IdempotencySupport.fingerprint("K1", a))
                .isNotEqualTo(IdempotencySupport.fingerprint("K2", b));
    }

    @Test
    void separatorCharactersInTextDoNotCauseAmbiguity() {
        // 单字段内含分隔符：["a,b"] 不得与两个字段 ["a","b"] 的指纹相同；
        // "a|b/c:d{e}f" 等合法文本同样保持结构化区分（依赖 JSON 数组/对象边界而非拼接分隔符）。
        ControlledProductionRequest joined =
                request("K1", "L-1", "a,b|c/d:e{f}", List.of(), 1, 1000L);
        ControlledProductionRequest split =
                request("K1", "L-1", "different-batch-but-compared-only-via-canonical", List.of(),
                        1, 1000L);
        assertThat(IdempotencySupport.fingerprint("K1", joined))
                .isNotEqualTo(IdempotencySupport.fingerprint("K1", split));

        // 过敏原集合：单元素 "PEANUT,MILK" 与双元素 ["PEANUT","MILK"] 的规范化 JSON 不同。
        String single = com.example.starter.compliance.support.JsonSupport.canonical(
                java.util.Map.of("allergens", List.of("PEANUT,MILK")));
        String two = com.example.starter.compliance.support.JsonSupport.canonical(
                java.util.Map.of("allergens", List.of("PEANUT", "MILK")));
        assertThat(single).isNotEqualTo(two);
    }

    @Test
    void bigDecimalTrailingZerosAreEquivalentButTextIsNot() {
        // 数字 10 与 10.00 视为同参；但字符串 "10" 与数字 10 类型不同，指纹不同。
        ControlledProductionRequest ten = new ControlledProductionRequest("K1", "L-1", 7L, "B",
                new BigDecimal("10"), 1, 1000L, null);
        ControlledProductionRequest tenZeros = new ControlledProductionRequest("K1", "L-1", 7L,
                "B", new BigDecimal("10.00"), 1, 1000L, null);
        assertThat(IdempotencySupport.fingerprint("K1", ten))
                .isEqualTo(IdempotencySupport.fingerprint("K1", tenZeros));
    }
}
