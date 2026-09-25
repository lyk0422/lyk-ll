package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 仅含幂等请求ID的请求（签发、归档）。
 *
 * @param requestId 幂等请求ID
 */
public record SimpleRequest(@NotBlank String requestId) {
}
