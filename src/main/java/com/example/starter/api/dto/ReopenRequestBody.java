package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 复开申请请求。
 */
public record ReopenRequestBody(@NotBlank String requestId) {
}
