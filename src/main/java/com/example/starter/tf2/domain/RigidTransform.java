package com.example.starter.tf2.domain;

/**
 * 三维刚体变换 T = (t, q)，表示子坐标系原点在父坐标系中的位姿：
 * p_parent = q * p_child + t。
 *
 * @param translation 平移（米）
 * @param rotation    旋转单位四元数
 */
public record RigidTransform(Vec3 translation, Quat rotation) {

    /** 恒等变换。 */
    public static final RigidTransform IDENTITY = new RigidTransform(Vec3.ZERO, Quat.IDENTITY);

    /**
     * 变换复合：返回 this ∘ other。
     * 先施加 other 再施加 this：
     * t = t1 + q1*t2，q = q1*q2。
     */
    public RigidTransform compose(RigidTransform other) {
        Quat q = Quat.multiply(this.rotation, other.rotation);
        Vec3 t = this.translation.add(this.rotation.rotate(other.translation));
        return new RigidTransform(t, q);
    }

    /**
     * 求逆变换：q^{-1}=q*（单位四元数共轭），t' = -q* * t。
     */
    public RigidTransform inverse() {
        Quat qInv = rotation.conjugate();
        Vec3 tInv = qInv.rotate(translation).scale(-1.0);
        return new RigidTransform(tInv, qInv);
    }

    /**
     * 在两样本之间按比例 u∈[0,1] 插值：平移线性插值、旋转四元数球面插值。
     */
    public static RigidTransform interpolate(RigidTransform a, RigidTransform b, double u) {
        return new RigidTransform(
                a.translation.lerp(b.translation, u),
                Quat.slerp(a.rotation, b.rotation, u));
    }
}
