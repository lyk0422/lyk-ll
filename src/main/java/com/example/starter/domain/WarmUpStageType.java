package com.example.starter.domain;

/**
 * 升温执行阶段类型。
 */
public enum WarmUpStageType {
    /** 升温段：按合成曲线定义的目标温度与最长用时执行。 */
    RAMP,
    /** 保温段：升温段恰满最长用时未达目标时自动转入。 */
    HOLD
}
