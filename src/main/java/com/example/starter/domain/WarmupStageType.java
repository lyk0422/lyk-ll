package com.example.starter.domain;

/**
 * 升温曲线阶段类型。
 */
public enum WarmupStageType {
    /** 升温段：设目标温度与最长用时，恰满最长用时未达目标自动转保温段。 */
    HEATING,
    /** 保温段：由升温段超时自动转入，恰满 4 小时升级告警。 */
    HOLDING
}
