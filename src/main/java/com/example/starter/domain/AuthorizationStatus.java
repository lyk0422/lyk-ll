package com.example.starter.domain;

/**
 * 授权状态：ACTIVE=生效中，COMPLETED=作业完成已释放，CANCELLED=已取消释放。
 */
public enum AuthorizationStatus {
    ACTIVE,
    COMPLETED,
    CANCELLED
}
