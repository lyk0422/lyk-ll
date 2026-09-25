package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 仅含幂等键的请求(发车确认/销记)。
 */
public record RequestIdRequest(@NotBlank String requestId) {
}
