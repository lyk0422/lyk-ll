package com.example.starter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 受控分段指令的参与机组登记项。
 *
 * @param turbineCode     机组编码（须属于该风场，不可重复）
 * @param startPowerKw    起始整数 kW 实际功率，须在原最低保留与当时可用出力之间
 * @param rampKwPerMin    每分钟正整数升降上限（kW）
 * @param deadlineMinutes 期限分钟数 1..15；期限内须能按上限到达冻结目标，否则全单拒绝
 */
public record StagedTurbineInput(
        @NotBlank String turbineCode,
        @PositiveOrZero int startPowerKw,
        @Positive int rampKwPerMin,
        @Min(1) @Max(15) int deadlineMinutes) {
}
