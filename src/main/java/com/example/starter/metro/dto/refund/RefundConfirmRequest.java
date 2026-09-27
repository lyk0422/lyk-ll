package com.example.starter.metro.dto.refund;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 退款双人最终确认请求：两名不同复核人及其确认精确时刻在最终确认事务内共同校验。
 *
 * @param requestId      幂等键
 * @param refundNo       退款单号
 * @param expectedVersion 退款单期望版本（待确认退款单为 1）
 * @param confirmerOne   第一复核人工号
 * @param confirmerOneTime 第一复核人确认精确时刻（Asia/Shanghai）
 * @param confirmerTwo   第二复核人工号，必须与第一复核人不同
 * @param confirmerTwoTime 第二复核人确认精确时刻（Asia/Shanghai）
 */
public record RefundConfirmRequest(
        @NotBlank String requestId,
        @NotBlank String refundNo,
        @NotNull @Positive Integer expectedVersion,
        @NotBlank String confirmerOne,
        @NotNull LocalDateTime confirmerOneTime,
        @NotBlank String confirmerTwo,
        @NotNull LocalDateTime confirmerTwoTime) {
}
