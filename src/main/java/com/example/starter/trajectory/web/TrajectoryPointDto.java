package com.example.starter.trajectory.web;

import jakarta.validation.constraints.NotNull;

/**
 * 规划轨迹点 DTO。
 */
public record TrajectoryPointDto(double t, @NotNull PointDto position, double heading) {
}
