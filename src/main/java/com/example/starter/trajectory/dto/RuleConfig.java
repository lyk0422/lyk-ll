package com.example.starter.trajectory.dto;

import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * 版本化安全规则配置。配置发布后不可原地修改，只能以新的 {@code ruleVersion} 发布新版本；
 * 同一轨迹可分别按不同版本复核。
 *
 * @param maxTimeInterval 相邻轨迹点最大允许时间间隔，单位秒，超出即失败
 * @param maxSpeed        最大允许速度，单位米每秒
 * @param maxAcceleration 最大允许加速度绝对值，单位米每二次方秒
 * @param maxCurvature    最大允许曲率绝对值，单位 1/米
 * @param roadTolerance   道路容差，单位米；点在多边形外但距离不超过容差仍合法
 * @param safetyDistance  碰撞安全距离，单位米；车与障碍物距离小于或等于该值即碰撞风险
 */
public record RuleConfig(
        @Positive BigDecimal maxTimeInterval,
        @Positive BigDecimal maxSpeed,
        @Positive BigDecimal maxAcceleration,
        @Positive BigDecimal maxCurvature,
        @Positive BigDecimal roadTolerance,
        @Positive BigDecimal safetyDistance) {
}
