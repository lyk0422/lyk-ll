package com.example.starter.sync;

/**
 * 传感器类型。四类传感器各自独立接收消息、维护水位线与延迟容忍窗口。
 */
public enum SensorType {
    LIDAR,
    RADAR,
    CAMERA,
    IMU
}
