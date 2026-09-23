package com.example.starter.scene.dto;

/**
 * 三维位置与四元数姿态。位置单位米，四元数无量纲；所有分量必须为有限数。
 *
 * @param x 位置 X，单位米
 * @param y 位置 Y，单位米
 * @param z 位置 Z，单位米
 * @param qx 四元数 X，无量纲
 * @param qy 四元数 Y，无量纲
 * @param qz 四元数 Z，无量纲
 * @param qw 四元数 W，无量纲
 */
public record Pose(double x, double y, double z, double qx, double qy, double qz, double qw) {

    /**
     * 位于原点的单位姿态，用于缺省补全。
     */
    public static Pose identity() {
        return new Pose(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0);
    }

    /**
     * 校验所有分量为有限数。
     *
     * @return 全部有限时返回 true
     */
    public boolean isFinite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && Double.isFinite(qx) && Double.isFinite(qy) && Double.isFinite(qz) && Double.isFinite(qw);
    }
}
