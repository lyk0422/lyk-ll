package com.example.starter.compliance.repo;

import java.util.List;

/**
 * 清线凭证行。
 *
 * @param operatorId        操作人工号
 * @param reviewerId        复核人工号（必须与操作人不同）
 * @param cleanedAt         清线时刻（UTC epoch 毫秒）
 * @param confirmedAt       双人确认时刻（UTC epoch 毫秒）
 * @param occupiedVersion   确认时线路占用版本
 * @param coveredAllergens  覆盖过敏原编码集合（升序）
 * @param status            VALID 有效 / CONSUMED 已核销
 * @param consumedAt        核销时刻（UTC epoch 毫秒），未核销为空
 * @param controlledProductionId 核销该凭证的受控量产记录主键，未核销为空
 */
public record ClearanceCertificateRow(
        Long id,
        String lineCode,
        String operatorId,
        String reviewerId,
        long cleanedAt,
        long confirmedAt,
        int occupiedVersion,
        List<String> coveredAllergens,
        String status,
        String requestId,
        Long consumedAt,
        Long controlledProductionId,
        long createdAt) {
}
