package com.example.starter.tf.math;

/**
 * 三维刚体变换：平移（米）加单位四元数旋转，Hamilton 约定。
 * 不可变值对象，所有运算返回新实例。
 *
 * <p>边 parent -&gt; child 上存储的变换语义为 {@code p_parent = T * p_child}，
 * 即 child 坐标系原点与轴向在 parent 坐标系中的位姿。
 */
public final class Transform3d {

    private static final double EPSILON = 1.0e-12;

    private final double tx;
    private final double ty;
    private final double tz;
    private final double qx;
    private final double qy;
    private final double qz;
    private final double qw;

    public Transform3d(double tx, double ty, double tz,
                       double qx, double qy, double qz, double qw) {
        this.tx = tx;
        this.ty = ty;
        this.tz = tz;
        this.qx = qx;
        this.qy = qy;
        this.qz = qz;
        this.qw = qw;
    }

    public static Transform3d identity() {
        return new Transform3d(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0);
    }

    /** 返回归一化四元数后的新变换；零四元数无法表示旋转。 */
    public Transform3d normalized() {
        double norm = Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
        if (norm < EPSILON) {
            throw new IllegalArgumentException("四元数模长为 0，无法归一化为旋转");
        }
        return new Transform3d(tx, ty, tz, qx / norm, qy / norm, qz / norm, qw / norm);
    }

    /** 位姿复合：{@code this * other}，先应用 other 再应用 this。 */
    public Transform3d multiply(Transform3d other) {
        double w = qw * other.qw - qx * other.qx - qy * other.qy - qz * other.qz;
        double x = qw * other.qx + qx * other.qw + qy * other.qz - qz * other.qy;
        double y = qw * other.qy - qx * other.qz + qy * other.qw + qz * other.qx;
        double z = qw * other.qz + qx * other.qy - qy * other.qx + qz * other.qw;

        double[] rotated = rotate(other.tx, other.ty, other.tz);
        return new Transform3d(tx + rotated[0], ty + rotated[1], tz + rotated[2],
                x, y, z, w);
    }

    /** 刚体逆变换。 */
    public Transform3d inverse() {
        Transform3d n = normalized();
        // 单位四元数的逆等于共轭
        Transform3d qInv = new Transform3d(0.0, 0.0, 0.0, -n.qx, -n.qy, -n.qz, n.qw);
        double[] tinv = qInv.rotate(-n.tx, -n.ty, -n.tz);
        return new Transform3d(tinv[0], tinv[1], tinv[2],
                -n.qx, -n.qy, -n.qz, n.qw);
    }

    /**
     * 在两个同尺度时刻的变换之间插值：
     * 平移线性插值，旋转四元数球面线性插值（取短弧）。
     *
     * @param alpha 区间 [0,1]，0 取 a，1 取 b
     */
    public static Transform3d interpolate(Transform3d a, Transform3d b, double alpha) {
        if (alpha <= 0.0) {
            return a;
        }
        if (alpha >= 1.0) {
            return b;
        }
        Transform3d na = a.normalized();
        Transform3d nb = b.normalized();

        double tx = na.tx + (nb.tx - na.tx) * alpha;
        double ty = na.ty + (nb.ty - na.ty) * alpha;
        double tz = na.tz + (nb.tz - na.tz) * alpha;

        double dot = na.qx * nb.qx + na.qy * nb.qy + na.qz * nb.qz + na.qw * nb.qw;
        double bx = nb.qx;
        double by = nb.qy;
        double bz = nb.qz;
        double bw = nb.qw;
        if (dot < 0.0) {
            // 取短弧：反向等价旋转
            dot = -dot;
            bx = -bx;
            by = -by;
            bz = -bz;
            bw = -bw;
        }

        double s0;
        double s1;
        if (dot > 0.9995) {
            // 角度极小，球面插值数值不稳定，退化为线性插值后归一化
            s0 = 1.0 - alpha;
            s1 = alpha;
        } else {
            double theta = Math.acos(Math.min(1.0, dot));
            double sinTheta = Math.sin(theta);
            s0 = Math.sin((1.0 - alpha) * theta) / sinTheta;
            s1 = Math.sin(alpha * theta) / sinTheta;
        }

        double qx = s0 * na.qx + s1 * bx;
        double qy = s0 * na.qy + s1 * by;
        double qz = s0 * na.qz + s1 * bz;
        double qw = s0 * na.qw + s1 * bw;
        return new Transform3d(tx, ty, tz, qx, qy, qz, qw).normalized();
    }

    /** 用当前旋转（先归一化）旋转一个向量。包内测试使用。 */
    private double[] rotate(double vx, double vy, double vz) {
        Transform3d n = normalized();
        // v' = q * v * q^{-1}（v 为纯四元数），展开为 v + 2 * cross(q.xyz, cross(q.xyz, v) + q.w * v)
        double ux = n.qx;
        double uy = n.qy;
        double uz = n.qz;
        double s = n.qw;

        double cx = uy * vz - uz * vy + s * vx;
        double cy = uz * vx - ux * vz + s * vy;
        double cz = ux * vy - uy * vx + s * vz;

        double rx = vx + 2.0 * (uy * cz - uz * cy);
        double ry = vy + 2.0 * (uz * cx - ux * cz);
        double rz = vz + 2.0 * (ux * cy - uy * cx);
        return new double[] {rx, ry, rz};
    }

    public double tx() {
        return tx;
    }

    public double ty() {
        return ty;
    }

    public double tz() {
        return tz;
    }

    public double qx() {
        return qx;
    }

    public double qy() {
        return qy;
    }

    public double qz() {
        return qz;
    }

    public double qw() {
        return qw;
    }
}
