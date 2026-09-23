package com.example.starter.calibration.domain;

/**
 * 标定参数适用的传感器类型。
 */
public enum SensorType {
    /** 相机：可存在内参与外参两类参数。 */
    CAMERA,
    /** 激光雷达：外参。 */
    LIDAR,
    /** 毫米波雷达：外参。 */
    RADAR,
    /** 惯性测量单元：IMU 参数。 */
    IMU
}
