package com.example.starter.tf2.domain;

/**
 * 单位四元数旋转（Hamilton 约定，分量顺序 x,y,z,w）。
 *
 * @param x 虚部 x
 * @param y 虚部 y
 * @param z 虚部 z
 * @param w 实部
 */
public record Quat(double x, double y, double z, double w) {

    /** 恒等旋转。 */
    public static final Quat IDENTITY = new Quat(0.0, 0.0, 0.0, 1.0);

    /** 归一化为单位四元数；输入为近零四元数时按恒等处理。 */
    public Quat normalized() {
        double n = Math.sqrt(x * x + y * y + z * z + w * w);
        if (n < 1e-12) {
            return IDENTITY;
        }
        return new Quat(x / n, y / n, z / n, w / n);
    }

    /** 四元数乘法 Hamilton 积 a*b。 */
    public static Quat multiply(Quat a, Quat b) {
        return new Quat(
                a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
                a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
                a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
                a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z);
    }

    /** 共轭（单位四元数的逆）。 */
    public Quat conjugate() {
        return new Quat(-x, -y, -z, w);
    }

    /** 将四元数作用于向量：q * v * q^{-1}。 */
    public Vec3 rotate(Vec3 v) {
        // t = 2 * (q_xyz × v)
        double tx = 2.0 * (y * v.z() - z * v.y());
        double ty = 2.0 * (z * v.x() - x * v.z());
        double tz = 2.0 * (x * v.y() - y * v.x());
        // v' = v + w*t + q_xyz × t
        double rx = v.x() + w * tx + (y * tz - z * ty);
        double ry = v.y() + w * ty + (z * tx - x * tz);
        double rz = v.z() + w * tz + (x * ty - y * tx);
        return new Vec3(rx, ry, rz);
    }

    /** 分量近似相等（幂等重放判定，先翻转到同一半球）。 */
    public boolean nearlyEquals(Quat o, double eps) {
        double sign = dot(this, o) < 0.0 ? -1.0 : 1.0;
        return Math.abs(x - sign * o.x) <= eps
                && Math.abs(y - sign * o.y) <= eps
                && Math.abs(z - sign * o.z) <= eps
                && Math.abs(w - sign * o.w) <= eps;
    }

    /**
     * 球面插值，t=0 取 a，t=1 取 b，自动走短弧；夹角极小时退化为归一化线性插值。
     */
    public static Quat slerp(Quat a, Quat b, double t) {
        Quat q0 = a.normalized();
        Quat q1 = b.normalized();
        double d = dot(q0, q1);
        if (d < 0.0) {
            q1 = new Quat(-q1.x, -q1.y, -q1.z, -q1.w);
            d = -d;
        }
        if (d > 0.9995) {
            // 夹角极小：nlerp，避免除零与抖动
            return new Quat(
                    q0.x + (q1.x - q0.x) * t,
                    q0.y + (q1.y - q0.y) * t,
                    q0.z + (q1.z - q0.z) * t,
                    q0.w + (q1.w - q0.w) * t).normalized();
        }
        double theta0 = Math.acos(Math.min(1.0, d));
        double theta = theta0 * t;
        double sinTheta0 = Math.sin(theta0);
        double s0 = Math.cos(theta) - d * Math.sin(theta) / sinTheta0;
        double s1 = Math.sin(theta) / sinTheta0;
        return new Quat(
                s0 * q0.x + s1 * q1.x,
                s0 * q0.y + s1 * q1.y,
                s0 * q0.z + s1 * q1.z,
                s0 * q0.w + s1 * q1.w).normalized();
    }

    private static double dot(Quat a, Quat b) {
        return a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w;
    }
}
