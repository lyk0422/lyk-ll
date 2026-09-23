package com.example.starter.costmap.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 创建不可变地图版本请求。
 *
 * @param mapId              可选地图版本 ID；缺省由服务端生成
 * @param width              栅格列数
 * @param height             栅格行数
 * @param resolution         每单元格物理边长（米/单元格）
 * @param originX            地图原点物理 x（米）
 * @param originY            地图原点物理 y（米）
 * @param inscribedRadius    内切膨胀半径（单元格）
 * @param inflationRadius    最大膨胀半径（单元格）
 * @param costScalingFactor  膨胀衰减系数
 * @param staticCells        静态格栅行优先数组，长度须等于 width*height，取值 0..254；可空表示全 0
 */
public record CreateMapRequest(
        @Size(max = 64) String mapId,
        @Min(1) @Max(4096) int width,
        @Min(1) @Max(4096) int height,
        @Min(0) double resolution,
        double originX,
        double originY,
        @Min(0) double inscribedRadius,
        @Min(0) double inflationRadius,
        @Min(0) double costScalingFactor,
        int[] staticCells) {
}
