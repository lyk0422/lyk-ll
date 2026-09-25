package com.example.starter.rolling.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 日终冻结请求。
 */
public record FreezeRequest(@NotBlank String requestId) {
}
