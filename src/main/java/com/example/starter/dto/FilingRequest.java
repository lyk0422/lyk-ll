package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 起爆备案请求。
 */
public record FilingRequest(
        @NotBlank String requestId,
        @NotNull Long permitId) {
}
