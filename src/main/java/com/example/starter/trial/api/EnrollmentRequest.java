package com.example.starter.trial.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 随机入组申请。
 *
 * @param requestId       幂等键（同一申请重放使用相同值）
 * @param trialId         试验编号
 * @param subjectScreenNo 受试者筛选号（同一试验内只能入组一次）
 * @param siteCode        中心编号（分层随机的层）
 * @param consentAtUtc    知情同意签署时刻（UTC）
 */
public record EnrollmentRequest(
        @NotBlank String requestId,
        @NotBlank String trialId,
        @NotBlank String subjectScreenNo,
        @NotBlank String siteCode,
        @NotNull Instant consentAtUtc) {
}
