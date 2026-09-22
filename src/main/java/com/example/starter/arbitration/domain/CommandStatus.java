package com.example.starter.arbitration.domain;

/**
 * 指令生命周期状态。
 */
public enum CommandStatus {
    /** 已通过校验、等待到时执行。 */
    PENDING,
    /** 已执行。 */
    EXECUTED,
    /** 校验失败（越界、超速、超限、回拨、过期等），未进入队列。 */
    REJECTED,
    /** 在队列中超过最大停留时间被超时清理并触发安全降级。 */
    TIMED_OUT
}
