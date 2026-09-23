package com.example.starter.evidence.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 清除最终确认请求。
 *
 * @param requestId       请求幂等键
 * @param expectedVersion 预期批次版本，标记态必须为 1
 */
public record ConfirmPurgeRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion) {
}
