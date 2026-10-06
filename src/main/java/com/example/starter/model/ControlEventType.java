package com.example.starter.model;

/**
 * 管制事件类型。
 */
public enum ControlEventType {
    /** 缓停：10 分钟平均风速恰达 17m/s 触发 */
    SLOW_STOP,
    /** 紧急停车：10 分钟平均风速恰达 20m/s 触发 */
    EMERGENCY_STOP
}
