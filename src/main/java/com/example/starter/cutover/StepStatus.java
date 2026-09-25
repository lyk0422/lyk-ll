package com.example.starter.cutover;

/**
 * 步骤执行状态：
 * PENDING 待执行；RUNNING 执行中；SUCCESS 成功；FAILED 失败；
 * FROZEN 已冻结（回退指令下达后未执行的后续步骤一律冻结）；SKIPPED 跳过。
 */
public enum StepStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    FROZEN,
    SKIPPED
}
