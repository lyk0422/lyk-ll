package com.example.starter.slam.po;

import java.time.Instant;

/**
 * 客户端优化请求与任务的查询关系。被合并请求保留行并记录 merged_into_task_id。
 *
 * @param requestId       客户端幂等键（唯一）
 * @param taskId          该请求最初创建/归属的任务 ID
 * @param graphId         目标图 ID
 * @param targetVersion   请求时记录的目标图版本
 * @param mergedIntoTaskId 若被合并，指向承接的存活任务 ID；否则为 null
 * @param createdAt       创建时间（UTC）
 */
public record TaskRequestPO(
        String requestId,
        String taskId,
        String graphId,
        long targetVersion,
        String mergedIntoTaskId,
        Instant createdAt
) {
}
