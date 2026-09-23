package com.example.starter.trajectory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 单个障碍物带唯一 ID 的预测轨迹，点按时间严格递增。
 *
 * @param id     障碍物唯一 ID，碰撞失败项通过该 ID 关联对象
 * @param points 障碍物预测点列表，按时间升序且至少两个点
 */
public record ObstaclePrediction(@NotBlank String id, @NotEmpty List<TimedPoint> points) {
}
