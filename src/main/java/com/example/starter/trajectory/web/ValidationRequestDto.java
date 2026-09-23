package com.example.starter.trajectory.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 轨迹安全校验请求 DTO。
 */
public record ValidationRequestDto(
        @NotBlank String requestId,
        @NotBlank String ruleVersion,
        @NotNull @Valid VehicleStateDto vehicle,
        @NotEmpty List<@Valid TrajectoryPointDto> trajectory,
        @NotNull List<@Valid ObstaclePredictionDto> obstacles,
        @NotNull List<@NotNull PointDto> road
) {
}
