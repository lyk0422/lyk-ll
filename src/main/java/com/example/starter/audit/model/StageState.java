package com.example.starter.audit.model;

/**
 * 阶段状态：待执行(PENDING)、运行(RUNNING)、成功(SUCCESS)、
 * 失败(FAILED)、已中断(INTERRUPTED)。
 */
public enum StageState {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    INTERRUPTED
}
