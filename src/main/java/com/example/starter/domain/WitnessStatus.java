package com.example.starter.domain;

/**
 * 见证链单条事件状态。
 */
public enum WitnessStatus {
    /** 见证缺席，随即转等待。 */
    ABSENT,
    /** 等待见证人到场。 */
    WAITING,
    /** 见证人到场签署。 */
    PRESENT,
    /** 等待满 2 小时（含边界）升级。 */
    ESCALATED
}
