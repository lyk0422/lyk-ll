package com.example.starter.deicing.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 出队确认请求体，以 requestId 幂等。
 */
public record DequeueRequest(@NotBlank String requestId) {
}
