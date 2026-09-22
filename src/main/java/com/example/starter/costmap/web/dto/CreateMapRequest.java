package com.example.starter.costmap.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 创建不可变地图版本请求。
 *
 * @param versionId 可选版本ID，缺省由服务端生成
 * @param width 栅格列数
 * @param height 栅格行数
 * @param resolution 米/格
 * @param inflationRadius 障碍物膨胀半径（米）
 * @param costDecay 膨胀代价指数衰减系数（1/米）
 * @param staticGrid 静态层代价矩阵（0..254，行优先 rows[y][x]），null 表示全 0
 */
public record CreateMapRequest(
        String versionId,
        @NotNull @Positive Integer width,
        @NotNull @Positive Integer height,
        @NotNull @Positive Double resolution,
        @NotNull @Min(0) Double inflationRadius,
        @NotNull @Min(0) Double costDecay,
        int[][] staticGrid) {
}
