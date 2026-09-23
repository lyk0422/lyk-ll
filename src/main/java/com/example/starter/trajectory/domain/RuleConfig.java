package com.example.starter.trajectory.domain;

/**
 * 版本化的安全规则配置。配置发布（status=PUBLISHED）后不可原地修改，
 * 需要调整时必须新建 ruleVersion；同一轨迹可分别按不同版本复核。
 *
 * @param ruleVersion        规则版本号，全局唯一
 * @param maxTimeGapSeconds  相邻轨迹点允许的最大时间间隔，单位秒
 * @param maxSpeed           最大纵向速度，单位米/秒
 * @param maxAcceleration    最大纵向加速度绝对值，单位米/秒^2
 * @param maxCurvature       最大曲率绝对值，单位 1/米
 * @param roadTolerance      道路容差，单位米，轨迹点在道路多边形外但距离不超过该值仍合法
 * @param collisionDistance  碰撞安全距离，单位米，车与障碍物距离小于或等于该值即视为碰撞风险
 * @param status             配置生命周期状态
 */
public record RuleConfig(
        String ruleVersion,
        double maxTimeGapSeconds,
        double maxSpeed,
        double maxAcceleration,
        double maxCurvature,
        double roadTolerance,
        double collisionDistance,
        RuleStatus status
) {
}
