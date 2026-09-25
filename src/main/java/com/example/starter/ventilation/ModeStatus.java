package com.example.starter.ventilation;

/**
 * 火灾模式生命周期状态。
 */
public enum ModeStatus {
    /** 已激活，排烟联动进行中 */
    ACTIVE,
    /** 火警解除，逆序复位进行中（复位完成前不接受新的激活） */
    RESETTING,
    /** 复位全部完成，等待归档 */
    RESET_COMPLETE,
    /** 归档终态：结论不再被迟到反馈改变 */
    ARCHIVED
}
