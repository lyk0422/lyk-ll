package com.example.starter.runway;

/**
 * 例行巡检计划状态机：
 * APPROVED 获准未开始 → IN_PROGRESS 巡检中 → COMPLETED 正常完成（终态）；
 * 紧急关闭抢占：未开始 → CANCELLED（终态），已开始 → ABORTED（终态）；
 * 航班日历变更：未开始 → NEEDS_REVIEW →（重审通过）APPROVED。
 */
public enum RoutinePlanStatus {
    APPROVED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
    ABORTED,
    NEEDS_REVIEW
}
