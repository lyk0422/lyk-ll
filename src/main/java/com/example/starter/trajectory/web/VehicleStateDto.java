package com.example.starter.trajectory.web;

import jakarta.validation.constraints.NotNull;

/**
 * 车辆初始状态 DTO。
 */
public record VehicleStateDto(
        @NotNull PointDto position,
        double heading,
        double speed
) {
}
