package com.example.starter.hazmat.po;

import java.time.LocalDateTime;

/**
 * 运单处置登记：未在夜间禁行起点前完成换班被冻结时，与冻结状态迁移在同一事务内写入。
 *
 * @param id         主键
 * @param shipmentId 运单 ID
 * @param relayId    触发处置的接力段 ID；无接力场景为 null
 * @param reason     处置原因
 * @param createdAt  登记时刻，Asia/Shanghai
 */
public record DisposalRecord(
        long id,
        long shipmentId,
        Long relayId,
        String reason,
        LocalDateTime createdAt) {
}
