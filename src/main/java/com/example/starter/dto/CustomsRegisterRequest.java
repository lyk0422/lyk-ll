package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * 进口通关单登记请求（合成数据）。
 *
 * @param requestId      幂等键
 * @param declarationNo  合成通关单号（全局唯一）
 * @param enterpriseName 进口企业
 * @param productName    疫苗品种
 * @param validUntil     通关有效截止时刻（须晚于当前业务时刻，恰截止拒绝签发）
 * @param totalDoses     可核销总支数（正整数）
 */
public record CustomsRegisterRequest(
        @NotBlank String requestId,
        @NotBlank String declarationNo,
        @NotBlank String enterpriseName,
        @NotBlank String productName,
        @NotNull Instant validUntil,
        @NotNull @Positive Integer totalDoses
) {
}
