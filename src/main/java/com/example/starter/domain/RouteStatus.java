package com.example.starter.domain;

/**
 * 线路状态：APPLIED 已申请，QUALIFIED 评估通过，IN_PROGRESS 执行中，SUSPENDED 停运。
 */
public enum RouteStatus {
    APPLIED,
    QUALIFIED,
    IN_PROGRESS,
    SUSPENDED
}
