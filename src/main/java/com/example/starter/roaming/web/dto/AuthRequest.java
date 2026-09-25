package com.example.starter.roaming.web.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 即插即充授权请求（同时建立会话）。
 *
 * @param requestId        幂等键
 * @param certFingerprint  车辆证书指纹
 * @param pointId          桩编号
 * @param requestTime      请求时刻（UTC）
 */
public record AuthRequest(
        @NotBlank String requestId,
        @NotBlank String certFingerprint,
        @NotBlank String pointId,
        @NotNull OffsetDateTime requestTime) {
}
