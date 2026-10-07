package com.example.starter.domain;

/**
 * 受控分段计划版本状态。
 * EXECUTING 执行中；PAUSED 因机组失效暂停（已执行历史不回退，可取消释放占用）；
 * CANCELLED 暂停后取消（终态）；COMPLETED 最后一分钟确认完成（终态）；
 * REPLACED 被重规划的新版本替换（历史保留，不再执行）。
 */
public enum ControlledPlanStatus {
    EXECUTING,
    PAUSED,
    CANCELLED,
    COMPLETED,
    REPLACED
}
