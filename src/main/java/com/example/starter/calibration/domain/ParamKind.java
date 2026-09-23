package com.example.starter.calibration.domain;

/**
 * 标定参数类别：相机内参 / 传感器外参 / IMU 参数。
 */
public enum ParamKind {
    /** 相机内参（焦距、主点、畸变等）。 */
    INTRINSIC,
    /** 相机、LiDAR、Radar 相对车体坐标系的外参（旋转 + 平移）。 */
    EXTRINSIC,
    /** IMU 参数（零偏、比例因子、噪声等）。 */
    IMU
}
