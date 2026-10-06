package com.example.starter.domain;

/**
 * 管制事件类型。
 */
public enum ControlEventType {
    /** 缓停：任一分段 10 分钟平均风速达到 17m/s（含）触发。 */
    SOFT_HOLD,
    /** 紧急停车：任一分段 10 分钟平均风速达到 20m/s（含）触发。 */
    EMERGENCY_STOP
}
