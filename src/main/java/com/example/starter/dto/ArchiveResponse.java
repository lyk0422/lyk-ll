package com.example.starter.dto;

import java.time.Instant;

/**
 * 归档响应。归档为终态，重复归档返回幂等成功。
 *
 * @param requestId  请求幂等键
 * @param taskId     调节任务ID
 * @param archived   是否处于已归档终态（重复归档仍为 true）
 * @param archivedAt 首次归档时刻(UTC)
 * @param replay     是否为同键同参重放返回
 */
public record ArchiveResponse(
        String requestId,
        Long taskId,
        boolean archived,
        Instant archivedAt,
        boolean replay
) {
}
