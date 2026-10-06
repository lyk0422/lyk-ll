package com.example.starter.waste;

/**
 * 联单状态：IN_PROGRESS 执行中；CLOSED 回执闭环；EXCEPTION 偏差异常核查中。
 */
public enum ManifestStatus {
    IN_PROGRESS,
    CLOSED,
    EXCEPTION
}
