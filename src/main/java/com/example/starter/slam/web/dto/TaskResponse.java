package com.example.starter.slam.web.dto;

import com.example.starter.slam.domain.OptimizationResult;
import com.example.starter.slam.domain.OptimizationTask;

import java.time.Instant;

/**
 * 优化任务查询响应；mergedIntoId 非空表示该请求被合并进另一任务，查询关系可继续追踪。
 */
public record TaskResponse(
        Long id,
        String requestId,
        int targetGraphVersion,
        String status,
        Long mergedIntoId,
        int retryCount,
        String failureReason,
        Integer resultGraphVersion,
        Double finalCost,
        String posesJson,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
    public static TaskResponse of(OptimizationTask task, OptimizationResult result) {
        return new TaskResponse(
                task.id(),
                task.requestId(),
                task.targetGraphVersion(),
                task.status().name(),
                task.mergedIntoId(),
                task.retryCount(),
                task.failureReason(),
                result == null ? null : result.graphVersion(),
                result == null ? null : result.finalCost(),
                result == null ? null : result.posesJson(),
                task.createdAt(),
                task.startedAt(),
                task.finishedAt()
        );
    }
}
