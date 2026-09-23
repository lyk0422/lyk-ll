package com.example.starter.trajectory.dto;

import java.util.List;

/**
 * 失败项复核证据：触发规则的相邻轨迹点，以及碰撞时对应的相邻障碍物预测点。
 * 列表均按时间升序，仅包含首次触发所涉及的点；后续重复触发不会替换该证据。
 *
 * @param vehiclePoints 相邻车辆规划轨迹点（R1~R5 至少两个，R5 为单个越界点）
 * @param obstaclePoints 相邻障碍物预测点（仅 R6 出现，其余为空列表）
 */
public record FailureEvidence(List<TimedPoint> vehiclePoints, List<TimedPoint> obstaclePoints) {
}
