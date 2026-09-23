package com.example.starter.slam.domain;

import com.example.starter.slam.model.TaskStatus;

import java.time.Instant;

/**
 * 优化任务。
 *
 * @param targetGraphVersion 任务目标图版本
 * @param snapshotJson       失败重试所依据的输入快照（建任务时的有效图摘要数据）
 * @param snapshotDigest     输入快照摘要（哈希）
 * @param mergedIntoId       被合并任务指向最终执行任务的 ID；未被合并为 null
 * @param retryCount         已使用原快照执行的重试次数
 * @param failureReason      最近一次可重试失败原因；无失败为 null
 * @param startedAt          最近一次开始执行时间（UTC）
 * @param finishedAt         完成时间（UTC）
 */
public record OptimizationTask(
        Long id,
        String requestId,
        int targetGraphVersion,
        TaskStatus status,
        String snapshotJson,
        String snapshotDigest,
        Long mergedIntoId,
        int retryCount,
        String failureReason,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
}
