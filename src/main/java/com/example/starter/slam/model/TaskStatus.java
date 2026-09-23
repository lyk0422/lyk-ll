package com.example.starter.slam.model;

/**
 * 优化任务状态机：
 * PENDING（待执行，可能被合并）→ RUNNING（运行中）→
 * SUCCEEDED（成功）/ RETRYABLE_FAILED（可重试失败）/ SUPERSEDED（已被更新版本结果取代）。
 */
public enum TaskStatus {
    /**
     * 待执行；同一图上的多个 PENDING 请求会被合并为一个任务。
     */
    PENDING,
    /**
     * 优化器正在执行；运行中的任务不参与合并。
     */
    RUNNING,
    /**
     * 优化成功，结果已落库。
     */
    SUCCEEDED,
    /**
     * 可重试失败，已保存输入快照摘要、失败原因与重试次数；重试使用原快照。
     */
    RETRYABLE_FAILED,
    /**
     * 任务完成时已存在基于更多有效边的新版本结果，本任务结果被取代且不得覆盖新结果。
     */
    SUPERSEDED,
    /**
     * 请求已被合并进另一个 PENDING 任务；mergedIntoId 指向最终执行任务以保留查询关系。
     */
    MERGED
}
