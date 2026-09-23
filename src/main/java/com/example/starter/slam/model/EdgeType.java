package com.example.starter.slam.model;

/**
 * 位姿图边类型：里程计边或回环约束边。
 */
public enum EdgeType {
    /**
     * 相邻关键帧之间的里程计约束。
     */
    ODOMETRY,
    /**
     * 非相邻关键帧之间的回环闭合约束，需通过可信度阈值校验。
     */
    LOOP
}
