package com.example.starter.trajectory.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 障碍物预测 DTO。
 */
public record ObstaclePredictionDto(
        @NotBlank String objectId,
        @NotNull List<PointTimeDto> points
) {

    /**
     * 障碍物预测点 DTO。
     */
    public record PointTimeDto(double t, @NotNull PointDto position) {
    }
}
