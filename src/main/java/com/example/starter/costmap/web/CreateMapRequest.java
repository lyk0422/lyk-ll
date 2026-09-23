package com.example.starter.costmap.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * 创建地图请求。
 */
public record CreateMapRequest(
        @NotBlank String mapId,
        @Min(1) @Max(10000) int width,
        @Min(1) @Max(10000) int height,
        @Positive double resolution,
        @NotNull Double originX,
        @NotNull Double originY,
        @Min(0) @Max(1000) int inflationRadiusCells,
        @DecimalMin("0.0") @DecimalMax("1.0") double costDecay,
        @NotNull List<CellDto> staticCells) {

    /** 静态格栅单元。 */
    public record CellDto(@Min(0) int x, @Min(0) int y, @Min(0) @Max(100) int cost) {
    }
}
