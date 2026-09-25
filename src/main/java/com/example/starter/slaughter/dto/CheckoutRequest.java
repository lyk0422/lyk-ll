package com.example.starter.slaughter.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 出厂核销请求：终态操作，重复核销幂等成功。
 *
 * @param requestId 幂等键
 */
public record CheckoutRequest(
        @NotBlank String requestId) {
}
