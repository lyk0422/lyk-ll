package com.example.starter.audit;

/**
 * 任务状态：待执行 / 运行 / 成功 / 失败 / 已中断。
 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    INTERRUPTED
}
