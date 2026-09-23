package com.example.starter.trajectory.domain;

/**
 * 规划轨迹点。
 *
 * @param t        相对初始状态的时间戳，单位秒，必须严格递增且大于 0
 * @param position 规划位置，单位米
 * @param heading  规划航向角，单位弧度
 */
public record TrajectoryPoint(double t, Vec position, double heading) {
}
