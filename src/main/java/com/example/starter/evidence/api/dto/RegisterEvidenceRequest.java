package com.example.starter.evidence.api.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 证据登记请求。
 *
 * @param evidenceId  证据唯一标识
 * @param sha256      证据内容 SHA-256 摘要（64 位十六进制）
 * @param collectedAt 采集时间（UTC）
 * @param expiresAt   到期时间（UTC）
 */
public record RegisterEvidenceRequest(
        @NotBlank String evidenceId,
        @NotBlank @Pattern(regexp = "(?i)[0-9a-f]{64}", message = "必须为 64 位十六进制 SHA-256") String sha256,
        @NotNull Instant collectedAt,
        @NotNull Instant expiresAt) {
}
