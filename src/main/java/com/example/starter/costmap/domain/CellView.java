package com.example.starter.costmap.domain;

import java.util.List;

/**
 * 某区域的实时视图查询结果。
 *
 * @param mapId       地图 ID
 * @param version     当前地图版本
 * @param region      查询区域
 * @param cells       区域内每格代价（行主序，含静态、动态、膨胀信息）
 */
public record CellView(
        String mapId,
        long version,
        Region region,
        List<CellViewItem> cells) {

    /**
     * 单格视图。
     *
     * @param x              格坐标 x
     * @param y              格坐标 y
     * @param staticCost     静态层代价 0-100
     * @param obstacleCost   动态层获胜来源上报代价；无来源为 null
     * @param inflatedCost   膨胀层贡献代价 0-100
     * @param finalCost      合并后的最终代价 0-100
     * @param winnerSourceId 获胜来源 ID；无动态来源为 null
     * @param winnerType     获胜来源类型；无动态来源为 null
     */
    public record CellViewItem(
            int x,
            int y,
            int staticCost,
            Integer obstacleCost,
            int inflatedCost,
            int finalCost,
            String winnerSourceId,
            String winnerType) {
    }
}
