package com.example.starter.metro.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 票卡关闭请求：关闭原票卡不影响该卡已成功退款的历史重放。
 *
 * @param requestId       幂等键
 * @param cardId          票卡编号
 * @param closeTime       关闭精确时刻（Asia/Shanghai）
 * @param expectedVersion 票卡期望版本
 */
public record CardCloseRequest(
        @NotBlank String requestId,
        @NotBlank String cardId,
        @NotNull LocalDateTime closeTime,
        @Positive @NotNull Integer expectedVersion) {
}
