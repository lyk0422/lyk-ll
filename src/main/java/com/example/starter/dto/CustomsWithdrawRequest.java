package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 签发前撤回子批请求：释放预占且只释放一次。
 *
 * @param requestId       幂等键
 * @param expectedVersion 通关单期望版本
 */
public record CustomsWithdrawRequest(
        @NotBlank String requestId,
        @NotNull Integer expectedVersion
) {
}
