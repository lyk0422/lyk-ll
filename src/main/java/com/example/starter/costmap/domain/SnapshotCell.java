package com.example.starter.costmap.domain;

/**
 * 快照中单个格栅发布时刻的分层代价。
 *
 * @param x              格坐标 x
 * @param y              格坐标 y
 * @param staticCost     静态层代价 0-100
 * @param obstacleCost   动态获胜层代价；无来源为 null
 * @param inflatedCost   膨胀层代价 0-100
 * @param finalCost      最终合并代价 0-100
 * @param winnerSourceId 获胜来源 ID；无动态来源为 null
 * @param winnerType     获胜来源类型名称；无动态来源为 null
 */
public record SnapshotCell(
        int x,
        int y,
        int staticCost,
        Integer obstacleCost,
        int inflatedCost,
        int finalCost,
        String winnerSourceId,
        String winnerType) {
}
