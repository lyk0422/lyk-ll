package com.example.starter.domain;

/**
 * 受控分段计划生命周期状态。
 * EXECUTING 执行中（占用机组）；PAUSED 机组可用状态失效暂停（已执行历史不回退，可取消释放占用）；
 * COMPLETED 最后一分钟确认完成（释放占用）；CANCELED 暂停后取消或指令作废/封存（释放占用，不恢复原功率）。
 */
public enum StagedPlanStatus {
    EXECUTING,
    PAUSED,
    COMPLETED,
    CANCELED
}
