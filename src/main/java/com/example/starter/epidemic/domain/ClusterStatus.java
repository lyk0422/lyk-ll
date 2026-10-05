package com.example.starter.epidemic.domain;

/**
 * 聚集事件状态：OPEN 进行中 / CONFIRMED 协查确认 / EXCLUDED 协查排除 / CLOSED 已关闭（终态）。
 */
public enum ClusterStatus {
    OPEN,
    CONFIRMED,
    EXCLUDED,
    CLOSED
}
