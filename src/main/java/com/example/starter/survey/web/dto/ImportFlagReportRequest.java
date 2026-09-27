package com.example.starter.survey.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * 导入外部船旗国检查证据报告；requestId 幂等。
 * 报告编号全局唯一：同参重复不新增，异内容冲突；仅接受合成签认标记。
 */
public record ImportFlagReportRequest(
        @NotBlank String requestId,
        @NotBlank String reportNo,
        @NotBlank String imo,
        @NotBlank String flagCode,
        @NotNull Instant completedAt,
        @NotBlank String syntheticSignoff,
        @NotEmpty List<@Valid FlagReportItemInput> items
) {
}
