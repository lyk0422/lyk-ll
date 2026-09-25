package com.example.starter.bonded.domain;

import java.time.Instant;

/**
 * 状态变更留痕持久化对象。
 *
 * @param id 主键
 * @param orderId 转关单主键
 * @param fromStatus 变更前状态，初始为 null
 * @param toStatus 变更后状态
 * @param changedAt 变更时间（UTC）
 * @param note 变更说明
 */
public record HistoryPo(
        Long id,
        Long orderId,
        TransferStatus fromStatus,
        TransferStatus toStatus,
        Instant changedAt,
        String note) {
}
