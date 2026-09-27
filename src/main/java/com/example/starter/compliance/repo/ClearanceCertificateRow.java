package com.example.starter.compliance.repo;

import java.util.List;

/**
 * 清线凭证行。
 *
 * @param clearedAllergens 凭证覆盖的过敏原编码集合（有序、去重）
 * @param clearedAt        清线时刻（UTC epoch 毫秒）
 * @param occupiedVersion  确认时固定的线路占用版本
 * @param status           ACTIVE 有效 / CONSUMED 已核销 / SUPERSEDED 已被后续量产作废
 * @param confirmedAt      双人共同确认时刻（UTC epoch 毫秒）；有效期为确认后 2 小时，恰满 2 小时失效
 */
public record ClearanceCertificateRow(
        Long id,
        String certificateNo,
        String lineCode,
        List<String> clearedAllergens,
        String operatorCode,
        String reviewerCode,
        long clearedAt,
        int occupiedVersion,
        String status,
        long confirmedAt,
        Long consumedAt) {
}
