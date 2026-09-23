package com.example.starter.planningscene.domain;

/**
 * 三维位姿：位置（米）加单位四元数姿态，相对所引用的坐标系。
 *
 * @param x 位置 X（米）
 * @param y 位置 Y（米）
 * @param z 位置 Z（米）
 * @param qx 四元数 X 分量
 * @param qy 四元数 Y 分量
 * @param qz 四元数 Z 分量
 * @param qw 四元数 W 分量
 */
public record Pose(double x, double y, double z, double qx, double qy, double qz, double qw) {

    /** 世界原点、无旋转的默认位姿。 */
    public static Pose origin() {
        return new Pose(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0);
    }
}
