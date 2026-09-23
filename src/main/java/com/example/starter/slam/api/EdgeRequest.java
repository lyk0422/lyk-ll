package com.example.starter.slam.api;

import com.example.starter.slam.domain.EdgeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 边写入请求（里程计边或回环约束）。
 *
 * @param id          边 ID；需与路径 edgeId 一致
 * @param requestId   客户端幂等键；同 ID 或同 requestId 改参返回 409
 * @param source      数据来源
 * @param version     数据版本（同时用于选择版本化回环阈值）
 * @param type        边类型 ODOMETRY / LOOP
 * @param fromFrameId 起点关键帧 ID，必须已存在且属于同一图
 * @param toFrameId   终点关键帧 ID，必须已存在且属于同一图
 * @param measurement 相对位姿测量 [dx, dy, dtheta]，单位米/弧度
 * @param information 信息矩阵（3x3 按行展开的 9 个值），用于回环协方差检查
 * @param score       回环匹配分数（0~1，越高越可信）；里程计边可为 null
 * @param residual    回环初始残差，单位与测量一致；里程计边可为 null
 */
public record EdgeRequest(
        String id,
        @NotBlank String requestId,
        @NotBlank String source,
        long version,
        @NotNull EdgeType type,
        @NotBlank String fromFrameId,
        @NotBlank String toFrameId,
        @NotNull @Size(min = 3, max = 3) List<Double> measurement,
        @Size(min = 9, max = 9) List<Double> information,
        Double score,
        Double residual
) {
}
