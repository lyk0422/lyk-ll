package com.example.starter;

import com.example.starter.service.ConflictException;
import com.example.starter.service.SealService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 幂等指纹结构化无歧义：合法文本含分隔符 '|' 时，字段边界移动必须产生不同指纹，
 * 不得误判为同参重放。
 */
class FingerprintStructureTest extends IntegrationTestBase {

    private static final Instant WINDOW_START = T0;
    private static final Instant WINDOW_END = T0.plus(Duration.ofHours(2));

    @Autowired
    private SealService sealService;

    @Test
    void 授权人文本含分隔符时字段边界移动仍判异参() {
        sealService.registerSeal(new SealService.RegisterSealCommand(
                "req-fp-seal", 1L, "SEAL-FP-1", 0));
        sealService.approveUnseal(new SealService.ApproveUnsealCommand(
                "req-fp-unseal", "SEAL-FP-1", 0, "张|三", "李", WINDOW_START, WINDOW_END));

        // 朴素分隔符拼接下指纹相同（"张|三|李"），结构化编码下必须判为异参 409
        assertThatThrownBy(() -> sealService.approveUnseal(new SealService.ApproveUnsealCommand(
                "req-fp-unseal", "SEAL-FP-1", 0, "张", "三|李", WINDOW_START, WINDOW_END)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 铅封号含分隔符时与单元编号边界移动仍判异参() {
        // unitId=1 + sealCode="SEAL|X" 与 不同字段切分在朴素拼接下可能混淆
        sealService.registerSeal(new SealService.RegisterSealCommand(
                "req-fp-seal-2", 1L, "SEAL|X", 0));

        // 同键但封号不同（"SEAL" + 另一字段吸收 "|X" 的朴素碰撞场景）必须判异参 409
        assertThatThrownBy(() -> sealService.registerSeal(new SealService.RegisterSealCommand(
                "req-fp-seal-2", 1L, "SEAL", 0)))
                .isInstanceOf(ConflictException.class);
    }
}
