package com.example.starter.costmap.dto;

/**
 * 版本快照视图（从 H2 读取，发布后不可变）。
 *
 * @param mapId           地图标识
 * @param version         快照对应的地图版本号
 * @param width           宽度（格）
 * @param height          高度（格）
 * @param resolution      分辨率（米/格）
 * @param inflationRadius 膨胀半径（米）
 * @param grid            行主序代价数组（0-255）
 * @param createdAt       快照发布时间（UTC，ISO-8601）
 */
public record SnapshotView(
        String mapId,
        long version,
        int width,
        int height,
        double resolution,
        double inflationRadius,
        int[] grid,
        String createdAt) {
}
