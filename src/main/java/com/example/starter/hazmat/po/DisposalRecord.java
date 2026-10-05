package com.example.starter.hazmat.po;

import java.time.LocalDateTime;

/**
 * 处置登记：未按时完成换班的运单冻结时在同一事务内登记。
 *
 * @param id           主键
 * @param shipmentId   运单 ID
 * @param relayId      关联接力段 ID；无接力段的冻结为 null
 * @param reason       处置原因
 * @param registeredAt 登记时刻（Asia/Shanghai）
 */
public record DisposalRecord(long id, long shipmentId, Long relayId, String reason,
                             LocalDateTime registeredAt) {
}
