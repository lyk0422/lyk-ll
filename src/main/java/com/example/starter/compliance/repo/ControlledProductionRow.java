package com.example.starter.compliance.repo;

import java.math.BigDecimal;

/**
 * 受控量产记录行（历史不可变）。
 *
 * @param occupiedVersion 该笔量产在该线路上使用的占用版本
 * @param certificateId   引用的清线凭证主键，无过敏原切换时为空
 * @param occurredAt      申报的精确生产时刻（UTC epoch 毫秒）
 */
public record ControlledProductionRow(
        Long id,
        String lineCode,
        long filingId,
        long formulaId,
        String batchNo,
        BigDecimal quantityKg,
        int occupiedVersion,
        Long certificateId,
        long occurredAt,
        String requestId,
        long createdAt) {
}
