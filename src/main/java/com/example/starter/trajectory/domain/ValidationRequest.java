package com.example.starter.trajectory.domain;

import java.util.List;

/**
 * 轨迹安全校验请求。
 *
 * @param requestId   幂等键，同键同参返回原结果，同键异参返回 409
 * @param ruleVersion 本次复核使用的规则版本
 * @param vehicle     车辆初始状态（t=0）
 * @param trajectory  按时间严格递增排列的规划轨迹点
 * @param obstacles   障碍物预测列表，objectId 唯一
 * @param road        道路边界
 */
public record ValidationRequest(
        String requestId,
        String ruleVersion,
        VehicleState vehicle,
        List<TrajectoryPoint> trajectory,
        List<ObstaclePrediction> obstacles,
        RoadBoundary road
) {
}
