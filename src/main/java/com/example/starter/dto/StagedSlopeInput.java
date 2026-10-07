package com.example.starter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 分段计划修改的机组斜率项：新的每分钟升降上限与期限；最终目标不变（仍由冻结分配决定）。
 */
public record StagedSlopeInput(
        @NotBlank String turbineCode,
        @Positive int rampKwPerMin,
        @Min(1) @Max(15) int deadlineMinutes) {
}
