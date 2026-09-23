package com.example.starter.slam.domain;

/**
 * 边类型：里程计边或回环约束边。
 */
public enum EdgeType {
    /**
     * 相邻关键帧之间的里程计约束。
     */
    ODOMETRY,
    /**
     * 非相邻关键帧之间的回环约束。
     */
    LOOP
}
