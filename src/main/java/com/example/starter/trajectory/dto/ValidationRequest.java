package com.example.starter.trajectory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 轨迹安全校验请求。
 *
 * @param requestId      幂等键；同键同参返回原结果，同键异参返回 409
 * @param ruleVersion    本次复核使用的不可变规则版本
 * @param initialState   车辆初始状态
 * @param trajectory     按时间排序的规划轨迹点，至少两个点
 * @param obstacles      带唯一 ID 的障碍物预测（可为空）
 * @param roadBoundary   道路边界
 */
public record ValidationRequest(
        @NotBlank String requestId,
        @NotBlank String ruleVersion,
        @NotNull @Valid VehicleState initialState,
        @NotEmpty @Valid @Size(min = 2, message = "轨迹至少需要两个点") List<TimedPoint> trajectory,
        @Valid List<ObstaclePrediction> obstacles,
        @NotNull @Valid RoadBoundary roadBoundary) {
}
