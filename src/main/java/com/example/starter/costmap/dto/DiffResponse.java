package com.example.starter.costmap.dto;

import com.example.starter.costmap.model.Region;

import java.util.List;

/**
 * 区域版本差异响应。
 *
 * @param mapId       地图标识
 * @param fromVersion 起始版本号
 * @param toVersion   目标版本号
 * @param region      查询区域
 * @param changes     区域内发生变化的格子列表（行主序）
 */
public record DiffResponse(
        String mapId,
        long fromVersion,
        long toVersion,
        Region region,
        List<CellChange> changes) {

    /**
     * 单个格子的代价变化。
     *
     * @param x        格子 x 坐标
     * @param y        格子 y 坐标
     * @param fromCost 起始版本代价值
     * @param toCost   目标版本代价值
     */
    public record CellChange(int x, int y, int fromCost, int toCost) {
    }
}
