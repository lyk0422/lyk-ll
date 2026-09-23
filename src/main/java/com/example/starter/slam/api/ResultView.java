package com.example.starter.slam.api;

import java.time.Instant;
import java.util.List;

/**
 * 优化结果视图。
 *
 * @param resultId         结果 ID
 * @param taskId           产出该结果的任务 ID
 * @param graphId          图 ID
 * @param graphVersion     结果基于的有效图版本
 * @param optimizedPoses   优化后各关键帧位姿 [x, y, theta]，按帧 ID 排序
 * @param frameIds         与 optimizedPoses 对应的帧 ID 顺序
 * @param cost             优化终止代价
 * @param iterations       迭代次数
 * @param createdAt        生成时间（UTC）
 */
public record ResultView(
        String resultId,
        String taskId,
        String graphId,
        long graphVersion,
        List<String> frameIds,
        List<List<Double>> optimizedPoses,
        double cost,
        int iterations,
        Instant createdAt
) {
}
