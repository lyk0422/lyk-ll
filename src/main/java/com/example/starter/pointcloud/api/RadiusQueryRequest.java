package com.example.starter.pointcloud.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Radius（球体）查询请求；球面边界上的点必须包含。
 */
public record RadiusQueryRequest(
        @NotNull Double centerX,
        @NotNull Double centerY,
        @NotNull Double centerZ,
        @NotNull @PositiveOrZero Double radiusMeters) {
}
