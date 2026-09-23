package com.example.starter.slam.domain;

/**
 * 优化任务状态。
 */
public enum TaskStatus {
    /**
     * 待执行：可与同一图上后续尚未开始的请求合并。
     */
    PENDING,
    /**
     * 运行中：已被工作线程领取，快照固定，不再参与合并。
     */
    RUNNING,
    /**
     * 成功：结果已落库且为当前最新有效结果。
     */
    SUCCEEDED,
    /**
     * 可重试失败：保留输入快照、失败原因与重试次数，可按原快照重试。
     */
    FAILED_RETRYABLE,
    /**
     * 已被取代：已有基于更高图版本（更多有效边）的成功结果，本结果不得覆盖新结果。
     */
    SUPERSEDED
}
