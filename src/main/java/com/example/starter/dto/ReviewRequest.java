package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 监管复核解除冻结请求。
 */
public record ReviewRequest(
        @NotBlank String requestId,
        @NotNull Long mineId,
        @NotBlank String reviewer) {
}
