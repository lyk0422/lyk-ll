package com.example.starter.dispatch.domain;

/**
 * 调度令生命周期状态。
 */
public enum OrderStatus {

    /** 已下达，等待闸站签收。 */
    ISSUED,
    /** 已签收，正在逐门执行。 */
    ACKNOWLEDGED,
    /** 全部闸门回执收齐且终态判定完成（可能存在执行偏差告警）。 */
    COMPLETED,
    /** 超过执行时限未收齐回执，已超时升级并通知上级。 */
    TIMEOUT_ESCALATED,
    /** 签收前撤销，终态。 */
    CANCELLED,
    /** 签收后人工中止，终态，保留已执行闸门实际开度。 */
    ABORTED
}
