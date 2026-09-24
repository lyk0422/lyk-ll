package com.example.starter.ili.persistence;

import com.example.starter.ili.model.BatchStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * alignment_batch 表行记录。
 *
 * @param id             批次主键
 * @param requestId      客户端幂等键
 * @param segmentId      管段标识
 * @param oldRunId       旧检测回合标识
 * @param newRunId       新检测回合标识
 * @param oldInspectedAt 旧回合检测日期
 * @param newInspectedAt 新回合检测日期
 * @param fingerprint    规范化请求摘要（SHA-256）
 * @param status         批次状态
 * @param version        乐观锁版本号
 * @param createdAt      创建时间
 * @param sealedAt       封板时间，NULL 表示未封板
 */
public record BatchRow(long id, String requestId, String segmentId,
                       String oldRunId, String newRunId,
                       LocalDate oldInspectedAt, LocalDate newInspectedAt,
                       String fingerprint, BatchStatus status, long version,
                       LocalDateTime createdAt, LocalDateTime sealedAt) {
}
