package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 通关单修订请求：仅允许调增总支数（增加未占余量），不改企业与品种。
 *
 * @param requestId       幂等键
 * @param expectedVersion 通关单期望版本
 * @param newTotalDoses   修订后可核销总支数（须严格大于当前总数）
 */
public record CustomsReviseRequest(
        @NotBlank String requestId,
        @NotNull Integer expectedVersion,
        @NotNull @Positive Integer newTotalDoses
) {
}
