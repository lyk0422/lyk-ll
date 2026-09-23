package com.example.starter.trajectory.dto;

/**
 * 带时间戳的二维点，用于轨迹点与障碍物预测点。
 *
 * @param t 时间戳，单位秒（相对同一时间原点）
 * @param x 坐标 x，单位米
 * @param y 坐标 y，单位米
 */
public record TimedPoint(double t, double x, double y) {

    /** 转换为不带时间的平面点。 */
    public Point toPoint() {
        return new Point(x, y);
    }
}
