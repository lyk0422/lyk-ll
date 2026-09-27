package com.example.starter.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 通关单撤销请求：释放全部未签发子批预占并阻断签发，已签发证据保留并记风险事件。
 *
 * @param requestId       幂等键
 * @param expectedVersion 期望的通关单版本（乐观锁，不匹配拒绝）
 */
public record RevokeDeclarationRequest(
        @NotBlank String requestId,
        @NotNull @Min(1) Integer expectedVersion
) {
}
