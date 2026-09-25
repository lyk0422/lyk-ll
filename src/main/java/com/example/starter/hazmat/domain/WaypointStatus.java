package com.example.starter.hazmat.domain;

/**
 * 运单途经点状态：PENDING 待打卡；CHECKED 已打卡；TIMEOUT 超时未打卡（偏离预警）；
 * CANCELLED 改线生效时原路线剩余途经点终止。
 */
public enum WaypointStatus {
    PENDING,
    CHECKED,
    TIMEOUT,
    CANCELLED
}
