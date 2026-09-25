package com.example.starter.hazmat.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 仅带幂等键的操作请求（发运、核销）。
 */
public record IdempotentRequest(@NotBlank String requestId) {
}
