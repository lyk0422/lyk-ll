package com.example.starter.survey.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.List;

/** 报告更正：携带 expectedVersion（当前修订号），成功生成新修订并推进版本。 */
public record CorrectReportRequest(
        @NotBlank String requestId,
        @NotBlank String reportNo,
        @NotNull @Positive Integer expectedVersion,
        @NotNull Instant completedAt,
        @NotBlank String attestation,
        @NotEmpty List<ReportItemInput> items
) {
}
