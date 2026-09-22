package com.example.starter.sensor.domain;

/**
 * 支持的传感器类型。
 */
public enum SensorType {
    /** 激光雷达，离散帧，不做插值。 */
    LIDAR,
    /** 毫米波雷达，允许在相邻量测间线性插值。 */
    RADAR,
    /** 相机，离散帧，不做插值。 */
    CAMERA,
    /** 惯性测量单元，高频连续量测，允许线性插值。 */
    IMU
}
