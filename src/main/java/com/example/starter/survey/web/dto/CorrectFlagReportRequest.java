package com.example.starter.survey.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * 报告更正：生成新修订并原子使未签发检验的旧互认失效；requestId 幂等。
 * expectedVersion 为更正前最新修订号，成功更正后修订号加 1。
 */
public record CorrectFlagReportRequest(
        @NotBlank String requestId,
        @NotNull @Min(1) Integer expectedVersion,
        @NotNull Instant completedAt,
        @NotBlank String syntheticSignoff,
        @NotEmpty List<@Valid FlagReportItemInput> items
) {
}
