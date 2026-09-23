package com.example.starter.calibration.domain;

/**
 * 标定参数类别：相机内参、外参（相机/LiDAR/Radar）、IMU 参数。
 */
public enum CalibrationKind {
    INTRINSIC,
    EXTRINSIC,
    IMU_PARAMS
}
