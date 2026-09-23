package com.example.starter.slam.po;

import java.time.Instant;

/**
 * 优化结果持久化对象。
 *
 * @param resultId       结果 ID
 * @param taskId         产出任务 ID（唯一）
 * @param graphId        图 ID
 * @param graphVersion   结果基于的有效图版本
 * @param frameIdsJson   帧 ID 顺序 JSON
 * @param posesJson      与帧顺序对应的优化位姿 JSON
 * @param cost           优化终止代价
 * @param iterations     迭代次数
 * @param createdAt      生成时间（UTC）
 */
public record OptimizeResultPO(
        String resultId,
        String taskId,
        String graphId,
        long graphVersion,
        String frameIdsJson,
        String posesJson,
        double cost,
        int iterations,
        Instant createdAt
) {
}
