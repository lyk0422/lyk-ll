package com.example.starter.scene.repo;

/**
 * 附着物行。
 *
 * @param id 对象 ID
 * @param linkName 附着的机器人 Link
 * @param x 相对 Link 位置 X，单位米
 * @param y 相对 Link 位置 Y，单位米
 * @param z 相对 Link 位置 Z，单位米
 * @param qx 四元数 X
 * @param qy 四元数 Y
 * @param qz 四元数 Z
 * @param qw 四元数 W
 * @param sx 盒体尺寸 X，单位米
 * @param sy 盒体尺寸 Y，单位米
 * @param sz 盒体尺寸 Z，单位米
 */
public record AttachedObjectRow(String id, String linkName,
                                double x, double y, double z,
                                double qx, double qy, double qz, double qw,
                                double sx, double sy, double sz) {
}
