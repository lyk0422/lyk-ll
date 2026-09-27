package com.example.starter.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 通关单修订请求：仅允许调增可核销总支数（增加未占余量），不改历史核销与企业品种。
 *
 * @param requestId         幂等键
 * @param expectedVersion   期望的通关单版本（乐观锁，不匹配拒绝）
 * @param newTotalQuantity  修订后的可核销总支数（必须严格大于当前总数）
 */
public record ReviseDeclarationRequest(
        @NotBlank String requestId,
        @NotNull @Min(1) Integer expectedVersion,
        @NotNull @Min(1) Integer newTotalQuantity
) {
}
