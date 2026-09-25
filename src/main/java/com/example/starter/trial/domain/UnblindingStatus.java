package com.example.starter.trial.domain;

/**
 * 揭盲申请状态。
 */
public enum UnblindingStatus {
    /** 仅第一名授权人确认，尚未生效 */
    PENDING,
    /** 两名不同授权人确认完成，揭盲生效（终态） */
    COMPLETED
}
