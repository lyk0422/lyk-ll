package com.example.starter.enrollment;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 入组申请。consentSignedAt 为知情同意签署时刻（UTC，ISO-8601）。
 */
public record EnrollmentRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "screeningNo 不能为空") String screeningNo,
        @NotBlank(message = "centerCode 不能为空") String centerCode,
        @NotNull(message = "consentSignedAt 不能为空") Instant consentSignedAt) {
}
