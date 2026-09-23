package com.example.starter.slam.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 关键帧写入请求。
 *
 * @param id      关键帧 ID；需与路径 frameId 一致
 * @param source  数据来源（如 odometry/camera 等）
 * @param version 数据版本
 * @param pose    位姿测量数据 [x, y, theta]，单位米/弧度，坐标系由来源约定
 */
public record KeyFrameRequest(
        String id,
        @NotBlank String source,
        long version,
        @NotNull @Size(min = 3, max = 3) List<Double> pose
) {
}
