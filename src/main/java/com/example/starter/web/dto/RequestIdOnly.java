package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 仅含幂等键的请求（发车确认、销记）。
 *
 * @param requestId 幂等请求键
 */
public record RequestIdOnly(
        @NotBlank String requestId) {
}
