package com.example.starter.compliance.repo;

import java.math.BigDecimal;

/**
 * 受控量产记录行（经过敏原清线凭证门禁）。
 *
 * @param occupancyVersion 受理时使用的线路占用版本；同一线路该值唯一
 * @param certificateId    本次引用并核销的清线凭证 ID；无需凭证的切换为 NULL
 * @param occurredAt       客户端声明的精确生产时刻（UTC epoch 毫秒）
 */
public record LineProductionRecordRow(
        Long id,
        String lineCode,
        Integer occupancyVersion,
        Long formulaId,
        Long filingId,
        Long certificateId,
        String batchNo,
        BigDecimal quantityKg,
        Long occurredAt,
        String requestId,
        Long createdAt) {
}
