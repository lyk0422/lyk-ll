package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 解除保全请求：携带 requestId 与预期版本。
 */
public record ReleaseHoldRequest(
        @NotNull Long holdId,
        @NotBlank String requestId,
        @NotNull Long expectedVersion
) {
}
