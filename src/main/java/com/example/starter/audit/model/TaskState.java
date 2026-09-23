package com.example.starter.audit.model;

/**
 * 任务状态：待执行(PENDING)、运行(RUNNING)、成功(SUCCESS)、
 * 失败(FAILED)、已中断(INTERRUPTED)。
 */
public enum TaskState {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    INTERRUPTED
}
