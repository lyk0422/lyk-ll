package com.example.starter.metro.dto.refund;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 票卡关闭请求：用于验证关闭原票卡不影响已成功退款的历史重放。
 *
 * @param requestId 幂等键
 * @param cardId    票卡编号
 * @param closeTime 关闭精确时刻（Asia/Shanghai）
 */
public record CardCloseRequest(
        @NotBlank String requestId,
        @NotBlank String cardId,
        @NotNull LocalDateTime closeTime) {
}
