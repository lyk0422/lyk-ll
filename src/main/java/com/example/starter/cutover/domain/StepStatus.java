package com.example.starter.cutover.domain;

/**
 * 步骤状态：PENDING 待执行 -> RUNNING 执行中 -> DONE 完成 / FAILED 失败；
 * 回退指令下达后，未完成步骤在同一事务内冻结为 FROZEN。
 */
public enum StepStatus {

    PENDING,
    RUNNING,
    DONE,
    FAILED,
    FROZEN
}
