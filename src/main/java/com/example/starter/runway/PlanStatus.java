package com.example.starter.runway;

/**
 * 例行跑道巡检计划状态。
 * APPROVED 获准 → PENDING_REVIEW 待重审（日历变更）→ IN_PROGRESS 进行中 → COMPLETED 已完成；
 * 紧急关闭抢占：未开始 → CANCELLED 已取消，已开始 → ABORTED 已中止（保留已完成检查项）。
 */
public enum PlanStatus {
    /** 获准（已发布，日历版本与当前一致） */
    APPROVED,
    /** 待重审（日历已变更，尚未开始，须重新确认日历版本） */
    PENDING_REVIEW,
    /** 进行中（已开始巡检） */
    IN_PROGRESS,
    /** 已完成（终态，仅关闭计划，不触发跑道开放） */
    COMPLETED,
    /** 已取消（终态，未开始即被紧急关闭抢占） */
    CANCELLED,
    /** 已中止（终态，已开始被紧急关闭抢占，已完成检查项保留） */
    ABORTED
}
