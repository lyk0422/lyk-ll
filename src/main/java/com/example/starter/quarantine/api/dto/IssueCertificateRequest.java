package com.example.starter.quarantine.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 出证请求（产地检疫合格后出证）。requestId 为幂等键。
 */
public record IssueCertificateRequest(@NotBlank String requestId) {
}
