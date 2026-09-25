package com.example.starter.model;

/**
 * 风机状态；同一台风机同一时刻只服务一个仓。
 */
public enum FanStatus {
    /** 空闲 */
    IDLE,
    /** 已锁定到某个通风作业 */
    LOCKED
}
