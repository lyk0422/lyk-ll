package com.example.starter.costmap.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 障碍物更新请求。
 *
 * @param ttlMillis 存活毫秒数；null 表示不过期
 * @param expectedVersion 预期地图版本；null 不做版本冲突校验
 */
public record ObstacleUpdateRequest(
        @NotBlank String requestId,
        @NotBlank String sourceId,
        @NotBlank String sourceType,
        @NotNull RegionDto region,
        @Min(0) @Max(100) int cost,
        @NotNull Long eventTime,
        @Min(0) Long ttlMillis,
        @Min(1) Long expectedVersion) {

    /** 轴对齐区域，左闭右开。 */
    public record RegionDto(
            @Min(0) int x0,
            @Min(0) int y0,
            @Min(1) int x1,
            @Min(1) int y1) {
    }
}
