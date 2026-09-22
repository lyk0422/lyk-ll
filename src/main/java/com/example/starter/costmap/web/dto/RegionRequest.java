package com.example.starter.costmap.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 矩形区域请求参数。
 */
public record RegionRequest(
        @NotNull @Min(0) Integer xMin,
        @NotNull @Min(0) Integer yMin,
        @NotNull @Min(0) Integer xMax,
        @NotNull @Min(0) Integer yMax) {
}
