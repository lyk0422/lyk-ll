package com.example.starter.compliance.repo;

/**
 * 清线凭证行。
 *
 * @param cleanedAt 清线时刻（UTC epoch 毫秒）
 * @param confirmedAt 确认时刻（UTC epoch 毫秒）；确认后 2 小时内有效，恰满 2 小时失效
 * @param occupancyVersion 确认时固定的线路占用版本；线路随后任何量产先提交即失效
 * @param status ISSUED 有效 / CONSUMED 已核销
 */
public record VoucherRow(
        Long id,
        String lineCode,
        long cleanedAt,
        long confirmedAt,
        int occupancyVersion,
        String operatorName,
        String reviewerName,
        String status,
        Long consumedAt,
        long createdAt) {
}
