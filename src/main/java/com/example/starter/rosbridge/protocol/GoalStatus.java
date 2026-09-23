package com.example.starter.rosbridge.protocol;

/**
 * Action Goal 状态。
 * PENDING 执行中；CANCELLED 被取消；SUCCEEDED 成功；FAILED 失败。
 * 除 PENDING 外均为终态，终态之间不可迁移（取消与结果幂等）。
 */
public enum GoalStatus {
    PENDING,
    CANCELLED,
    SUCCEEDED,
    FAILED
}
