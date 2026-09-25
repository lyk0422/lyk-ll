package com.example.starter.model;

/**
 * 生产终态：生产中 / 已停产 / 已复工；同一测区任一时刻只能处于一种状态。
 */
public enum ProductionStatus {
    ACTIVE,
    STOPPED,
    RESUMED
}
