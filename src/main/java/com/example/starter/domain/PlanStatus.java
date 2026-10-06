package com.example.starter.domain;

/**
 * 修剪计划状态：PENDING待修剪，CLOSED已闭环，ESCALATED逾期升级督办，RESCHEDULED评级变化后已重排。
 */
public enum PlanStatus {
    PENDING,
    CLOSED,
    ESCALATED,
    RESCHEDULED
}
