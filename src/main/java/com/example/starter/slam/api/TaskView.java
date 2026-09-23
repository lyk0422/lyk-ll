package com.example.starter.slam.api;

import com.example.starter.slam.domain.TaskStatus;

import java.time.Instant;
import java.util.List;

/**
 * 优化任务视图/查询结果。
 *
 * @param taskId           任务 ID
 * @param requestIds       关联的全部客户端请求 ID（含被合并请求）
 * @param graphId          目标图 ID
 * @param targetVersion    请求记录的目标图版本；合并任务取其中最高版本
 * @param status           任务状态
 * @param attempts         已尝试次数（含首次执行）
 * @param failureReason    最近一次可重试失败的原因；无失败时为 null
 * @param snapshotSummary  输入快照摘要
 * @param resultVersion    成功结果对应的图版本；未成功为 null
 * @param mergedIntoTaskId 若该请求已被合并，指向承接任务 ID；否则为 null
 * @param createdAt        创建时间（UTC）
 * @param updatedAt        最近更新时间（UTC）
 */
public record TaskView(
        String taskId,
        List<String> requestIds,
        String graphId,
        long targetVersion,
        TaskStatus status,
        int attempts,
        String failureReason,
        String snapshotSummary,
        Long resultVersion,
        String mergedIntoTaskId,
        Instant createdAt,
        Instant updatedAt
) {
}
