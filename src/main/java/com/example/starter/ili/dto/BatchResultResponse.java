package com.example.starter.ili.dto;

import java.time.Instant;
import java.util.List;

/**
 * 对齐批次结果视图：提交与幂等重放返回同一内容。
 *
 * @param batchId     批次主键
 * @param requestId   客户端幂等请求标识
 * @param status      批次状态：ALIGNED/SEALED
 * @param createdAt   批次创建时间（UTC）
 * @param sealedAt    封板时间（UTC）；null 表示未封板
 * @param pairs       匹配对清单
 * @param newFindings 本回合新发现缺陷（未匹配的新读数）
 * @param disappeared 已消失缺陷（未匹配的旧读数）
 */
public record BatchResultResponse(
        long batchId,
        String requestId,
        String status,
        Instant createdAt,
        Instant sealedAt,
        List<PairView> pairs,
        List<ReadingView> newFindings,
        List<ReadingView> disappeared) {
}
