package com.example.starter.runway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 验收清场凭据提交请求（合成）：每段须两个不同验收人各提交一条。requestId 为幂等标识。
 *
 * @param requestId   幂等请求标识（指纹覆盖该字段与全部业务字段）
 * @param inspectorId 验收人标识（合成）；同段同一验收人仅计一次
 * @param credential  合成清场凭据文本
 * @param submittedAt 凭据提交时刻（UTC，精确时刻由请求给出）
 */
public record AcceptanceRequest(
        @NotBlank String requestId,
        @NotBlank String inspectorId,
        @NotBlank String credential,
        @NotNull Instant submittedAt) {
}
