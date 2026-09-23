package com.example.starter.scene.repo;

/**
 * 世界碰撞对象行。
 *
 * @param id 对象 ID
 * @param frameId 位姿参考坐标系
 * @param x 位置 X，单位米
 * @param y 位置 Y，单位米
 * @param z 位置 Z，单位米
 * @param qx 四元数 X
 * @param qy 四元数 Y
 * @param qz 四元数 Z
 * @param qw 四元数 W
 * @param sx 盒体尺寸 X，单位米
 * @param sy 盒体尺寸 Y，单位米
 * @param sz 盒体尺寸 Z，单位米
 */
public record WorldObjectRow(String id, String frameId,
                             double x, double y, double z,
                             double qx, double qy, double qz, double qw,
                             double sx, double sy, double sz) {
}
