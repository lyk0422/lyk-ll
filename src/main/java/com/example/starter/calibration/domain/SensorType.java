package com.example.starter.calibration.domain;

/**
 * 传感器类型。
 */
public enum SensorType {
    /** 相机：校验内参焦距为正。 */
    CAMERA,
    /** 激光雷达：仅校验外参。 */
    LIDAR,
    /** 毫米波雷达：仅校验外参。 */
    RADAR,
    /** 惯性测量单元：使用 IMU 专用参数字段。 */
    IMU
}
