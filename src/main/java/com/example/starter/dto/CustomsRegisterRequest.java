package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * 进口通关单登记请求（requestId 幂等）。登记成功后通关单版本从 1 开始。
 *
 * @param requestId      幂等键
 * @param customsNo      合成通关单号（全局唯一）
 * @param enterpriseName 登记企业（绑定子批企业必须一致）
 * @param productName    登记品种（绑定子批品种必须一致，历史核销不可改）
 * @param validUntil     有效截止精确时刻，签发要求 at &lt; validUntil，恰等于即失效
 * @param totalQuantity  可核销总支数（正整数，单位：支）
 * @param at             本次操作精确时刻（纳入幂等指纹，保证时刻边界确定）
 */
public record CustomsRegisterRequest(
        @NotBlank String requestId,
        @NotBlank String customsNo,
        @NotBlank String enterpriseName,
        @NotBlank String productName,
        @NotNull Instant validUntil,
        @Positive int totalQuantity,
        @NotNull Instant at
) {
}
