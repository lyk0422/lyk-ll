package com.example.starter.trajectory.domain;

/**
 * 车辆初始状态，对应规划起始时刻 t=0。
 *
 * @param position 初始位置，单位米
 * @param heading  初始航向角，单位弧度，范围 (-pi, pi]
 * @param speed    初始纵向速度，单位米/秒，非负
 */
public record VehicleState(Vec position, double heading, double speed) {
}
