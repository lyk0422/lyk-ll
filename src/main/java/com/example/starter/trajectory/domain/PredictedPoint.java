package com.example.starter.trajectory.domain;

/**
 * 障碍物预测轨迹点。
 *
 * @param t        预测时间戳，单位秒
 * @param position 预测位置，单位米
 */
public record PredictedPoint(double t, Vec position) {
}
