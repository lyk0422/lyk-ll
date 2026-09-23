package com.example.starter.slam.domain;

import java.time.Instant;

/**
 * 优化结果。
 *
 * @param taskId       产出该结果的任务 ID
 * @param graphVersion 结果所基于的有效图版本
 * @param posesJson    优化后位姿集合
 * @param finalCost    优化终止代价
 * @param iterations   优化器实际迭代次数
 * @param createdAt    落库时间（UTC）
 */
public record OptimizationResult(
        Long id,
        Long taskId,
        int graphVersion,
        String posesJson,
        double finalCost,
        Integer iterations,
        Instant createdAt
) {
}
