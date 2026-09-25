package com.example.starter.trial.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 随机入组申请。
 *
 * @param requestId      入组请求幂等键
 * @param screeningNo    受试者筛选号（同一试验内唯一）
 * @param centerCode     中心编码（按中心分层）
 * @param consentTimeUtc 知情同意签署时刻（UTC）
 */
public record EnrollRequest(
        @NotBlank String requestId,
        @NotBlank String screeningNo,
        @NotBlank String centerCode,
        @NotNull Instant consentTimeUtc) {
}
