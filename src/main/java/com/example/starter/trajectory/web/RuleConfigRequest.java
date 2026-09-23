package com.example.starter.trajectory.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 规则配置创建/更新请求。
 */
public record RuleConfigRequest(
        @NotBlank String ruleVersion,
        @Positive double maxTimeGapSeconds,
        @PositiveOrZero double maxSpeed,
        @PositiveOrZero double maxAcceleration,
        @PositiveOrZero double maxCurvature,
        @PositiveOrZero double roadTolerance,
        @PositiveOrZero double collisionDistance
) {
}
