package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 证据登记请求：内容为合成字节的 Base64，服务端计算 SHA-256。
 *
 * @param collectedAt 采集时间（UTC），为空时取服务端当前时间
 * @param expiresAt 到期时间（UTC），必填
 */
public record RegisterEvidenceRequest(
        @NotBlank String evidenceId,
        @NotBlank String contentBase64,
        Instant collectedAt,
        @NotNull Instant expiresAt
) {
}
