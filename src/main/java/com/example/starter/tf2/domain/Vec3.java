package com.example.starter.tf2.domain;

/**
 * 三维平移向量，单位与调用方约定一致（本题为无量纲合成坐标，单位：米）。
 *
 * @param x X 轴分量
 * @param y Y 轴分量
 * @param z Z 轴分量
 */
public record Vec3(double x, double y, double z) {

    /** 零向量。 */
    public static final Vec3 ZERO = new Vec3(0.0, 0.0, 0.0);

    /** 向量加法。 */
    public Vec3 add(Vec3 other) {
        return new Vec3(x + other.x, y + other.y, z + other.z);
    }

    /** 向量减法。 */
    public Vec3 subtract(Vec3 other) {
        return new Vec3(x - other.x, y - other.y, z - other.z);
    }

    /** 数乘。 */
    public Vec3 scale(double s) {
        return new Vec3(x * s, y * s, z * s);
    }

    /** 分量按位线性插值：t=0 取 this，t=1 取 other。 */
    public Vec3 lerp(Vec3 other, double t) {
        return new Vec3(
                x + (other.x - x) * t,
                y + (other.y - y) * t,
                z + (other.z - z) * t);
    }

    /** 分量近似相等（阈值 1e-9，用于幂等重放判定）。 */
    public boolean nearlyEquals(Vec3 other, double eps) {
        return Math.abs(x - other.x) <= eps
                && Math.abs(y - other.y) <= eps
                && Math.abs(z - other.z) <= eps;
    }
}
