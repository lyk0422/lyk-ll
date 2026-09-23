package com.example.starter.trajectory.dto;

/**
 * 车辆初始状态。
 *
 * @param x       初始位置 x，单位米
 * @param y       初始位置 y，单位米
 * @param heading 初始航向角，单位弧度
 * @param speed   初始速度，单位米每秒
 */
public record VehicleState(double x, double y, double heading, double speed) {
}
