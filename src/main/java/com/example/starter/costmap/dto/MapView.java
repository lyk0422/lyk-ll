package com.example.starter.costmap.dto;

/**
 * 地图视图：某一版本的完整栅格。
 *
 * @param mapId           地图标识
 * @param version         版本号，从 0 开始单调递增
 * @param width           宽度（格）
 * @param height          高度（格）
 * @param resolution      分辨率（米/格）
 * @param inflationRadius 膨胀半径（米）
 * @param grid            行主序代价数组（0-255：0 空闲，254 致命障碍，255 未知）
 */
public record MapView(
        String mapId,
        long version,
        int width,
        int height,
        double resolution,
        double inflationRadius,
        int[] grid) {
}
