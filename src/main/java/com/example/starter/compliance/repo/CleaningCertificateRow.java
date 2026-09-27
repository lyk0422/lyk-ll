package com.example.starter.compliance.repo;

/**
 * 清线凭证行。
 *
 * @param expectedVersion   确认时线路占用版本（线路版本），量产受理时必须与线路当前占用版本一致
 * @param occupiedFormulaId 确认时线路最近量产配方版本 ID（首产前确认时为 NULL）
 * @param cleanedAt         清线确认精确时刻（UTC epoch 毫秒）
 * @param allergenCodes     覆盖过敏原编码集合的结构化 JSON 数组
 * @param status            VALID 有效 / CONSUMED 已被量产核销 / SUPERSEDED 被后续生产作废旧证
 * @param consumedAt        核销或作废时刻（UTC epoch 毫秒），VALID 时为 NULL
 */
public record CleaningCertificateRow(
        Long id,
        String lineCode,
        Integer expectedVersion,
        Long occupiedFormulaId,
        String operatorId,
        String reviewerId,
        Long cleanedAt,
        String allergenCodes,
        String status,
        String requestId,
        Long consumedAt,
        Long createdAt) {
}
