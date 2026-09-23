package com.example.starter.trajectory.domain;

import java.util.List;

/**
 * 障碍物预测，objectId 在同一次校验请求内唯一。
 *
 * @param objectId 障碍物唯一标识
 * @param points   按时间严格递增排列的预测位置点
 */
public record ObstaclePrediction(String objectId, List<PredictedPoint> points) {
}
