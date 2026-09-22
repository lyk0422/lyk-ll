package com.example.starter.perception.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 单条本地检测输入（不接入真实感知模型/传感器，仅消费合成检测）。
 *
 * @param detectionId 检测唯一ID，重复提交按幂等处理
 * @param timestamp   检测时戳（UTC，ISO-8601，如 2026-09-22T10:00:00Z）
 * @param source      检测来源（如 camera_front / lidar_top），仅用于证据留痕
 * @param category    目标类别（如 pedestrian / vehicle），仅同类目标参与关联
 * @param x           检测位置X，单位米，局部平面坐标系
 * @param y           检测位置Y，单位米
 * @param confidence  检测置信度，范围[0,1]，低于阈值的检测被拒绝
 */
public record DetectionRequest(
        @NotBlank String detectionId,
        @NotNull Instant timestamp,
        @NotBlank String source,
        @NotBlank String category,
        double x,
        double y,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double confidence
) {
}
