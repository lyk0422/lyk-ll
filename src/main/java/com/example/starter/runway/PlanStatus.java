package com.example.starter.runway;

/**
 * 例行巡检计划状态机：
 * FROZEN 已冻结（发布成功，占用同跑道同窗唯一名额）→
 * REVIEW_PENDING 日历版本变更后待重审（释放名额）→
 * RUNNING 巡检中（重新确认日历版本且跑道无进行中紧急事件后开始）→
 * COMPLETED 正常完成（终态，仅关闭计划，不直接开放跑道）；
 * 紧急关闭：未开始计划 CANCELLED（终态），已开始计划 ABORTED（终态，检查项保留，迟到完成仅登记）。
 */
public enum PlanStatus {
    FROZEN,
    REVIEW_PENDING,
    RUNNING,
    COMPLETED,
    CANCELLED,
    ABORTED
}
