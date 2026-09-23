package com.example.starter.slam.po;

import com.example.starter.slam.domain.TaskStatus;

import java.time.Instant;

/**
 * 优化任务持久化对象。
 *
 * @param taskId          任务 ID
 * @param graphId         目标图 ID
 * @param targetVersion   请求记录的目标图版本；合并任务取被合并请求中的最高版本
 * @param status          任务状态
 * @param attempts        已尝试次数（首次执行计 1）
 * @param failureReason   最近一次可重试失败原因；无失败为 null
 * @param snapshotJson    固定输入快照 JSON；执行时构建，重试复用，不为新任务重建
 * @param snapshotSummary 输入快照摘要
 * @param resultVersion   成功结果对应的图版本；未成功为 null
 * @param createdAt       创建时间（UTC）
 * @param updatedAt       最近更新时间（UTC）
 * @param startedAt       最近一次开始执行时间（UTC）；未开始为 null
 * @param finishedAt      完成时间（UTC）；未结束为 null
 */
public record OptimizeTaskPO(
        String taskId,
        String graphId,
        long targetVersion,
        TaskStatus status,
        int attempts,
        String failureReason,
        String snapshotJson,
        String snapshotSummary,
        Long resultVersion,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant finishedAt
) {
}
