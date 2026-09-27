package com.example.starter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 日前计划单段单机明细输入。
 *
 * @param segmentIndex 时段序号 0..95（相对 planDate 00:00 UTC 的十五分钟半开区间）
 * @param turbineCode  机组编码（风场内机组）
 * @param powerKw      核定计划出力（kW，非负整数，不得超过该机组合成装机容量）
 */
public record PlanSegmentInput(
        @Min(0) @Max(95) int segmentIndex,
        @NotBlank String turbineCode,
        @PositiveOrZero int powerKw) {
}
