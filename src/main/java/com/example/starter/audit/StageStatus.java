package com.example.starter.audit;

/**
 * 阶段状态：待执行 / 运行 / 成功 / 失败 / 已中断。
 */
public enum StageStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    INTERRUPTED
}
