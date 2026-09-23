package com.example.starter.trajectory.domain;

/**
 * 二维坐标点，单位米。
 *
 * @param x 纵向/东向坐标，单位米
 * @param y 横向/北向坐标，单位米
 */
public record Vec(double x, double y) {

    /**
     * 计算与另一点的欧氏距离，单位米。
     */
    public double distanceTo(Vec other) {
        double dx = x - other.x;
        double dy = y - other.y;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
