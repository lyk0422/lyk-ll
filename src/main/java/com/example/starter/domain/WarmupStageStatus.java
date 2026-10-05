package com.example.starter.domain;

/**
 * 升温曲线阶段状态。
 */
public enum WarmupStageStatus {
    /** 待进入。 */
    PENDING,
    /** 执行中（含超时自动转入的保温段）。 */
    ACTIVE,
    /** 双人复核确认完成。 */
    CONFIRMED
}
