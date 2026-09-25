package com.example.starter.dispatch.domain;

/**
 * 调度令中单个闸门执行项的状态。
 */
public enum GateStepStatus {

    /** 待执行：前序闸门尚未回执完成，本门不得启动。 */
    PENDING,
    /** 已收到闸站回执（到位或偏差以 inPosition 区分）。 */
    RECEIVED
}
