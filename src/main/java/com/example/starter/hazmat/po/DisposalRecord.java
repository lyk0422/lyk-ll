package com.example.starter.hazmat.po;

import java.time.LocalDateTime;

/**
 * 禁行冻结处置登记：与运单冻结同一事务写入。
 *
 * @param id          主键
 * @param shipmentId  运单 ID
 * @param segmentId   被冻结的进行中接力段 ID；未设置接力点为 null
 * @param reason      处置原因
 * @param curfewStart 命中的夜间禁行起点
 * @param createdAt   登记时刻
 */
public record DisposalRecord(
        long id,
        long shipmentId,
        Long segmentId,
        String reason,
        LocalDateTime curfewStart,
        LocalDateTime createdAt) {
}
