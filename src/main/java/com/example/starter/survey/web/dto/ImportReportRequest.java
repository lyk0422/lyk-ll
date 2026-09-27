package com.example.starter.survey.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/** 导入外部船旗国检查报告；requestId 幂等，报告编号全局唯一。 */
public record ImportReportRequest(
        @NotBlank String requestId,
        @NotBlank String reportNo,
        @NotBlank String shipImo,
        @NotBlank String flagCode,
        @NotNull Instant completedAt,
        @NotBlank String attestation,
        @NotEmpty List<ReportItemInput> items
) {
}
