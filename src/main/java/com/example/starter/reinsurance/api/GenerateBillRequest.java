package com.example.starter.reinsurance.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 季度账单生成请求（按 UTC 日界归集）。
 *
 * @param requestId 幂等键
 * @param contractNo 合约号
 * @param period 季度标签，格式 yyyy-Qn（如 2026-Q3）
 */
public record GenerateBillRequest(
        @NotBlank String requestId,
        @NotBlank String contractNo,
        @NotBlank String period) {
}
