package com.example.starter.ros;

/**
 * ROS 参数支持的标量类型（模拟 rclcpp 参数类型中的常用标量）。
 */
public enum ParamType {
    /** 字符串 */
    STRING,
    /** 64 位整数 */
    INT,
    /** 双精度浮点数 */
    DOUBLE,
    /** 布尔 */
    BOOL
}
