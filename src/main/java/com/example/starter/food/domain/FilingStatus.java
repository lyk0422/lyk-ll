package com.example.starter.food.domain;

/**
 * 量产备案状态。
 */
public enum FilingStatus {
    /** 有效备案，允许量产上报 */
    ACTIVE,
    /** 复审观察：标准更新后旧版本已备案配方须在 90 个自然日内完成复审 */
    REVIEW_PENDING,
    /** 暂停备案：复审不合格、逾期未复审或被新版本替代 */
    SUSPENDED,
    /** 注销终态：注销后量产记录上报一律拒绝 */
    CANCELLED
}
