package com.example.starter.trial.api;

import java.time.Instant;

/**
 * 访视记录响应。
 *
 * @param visitAtUtc                访视时刻（UTC）
 * @param visitType                 访视类型
 * @param measureValue              测量值
 * @param excludedFromMainAnalysis  是否排除出主要分析集
 * @param exclusionReason           排除原因（SUBJECT_UNBLINDED；未排除为 null）
 */
public record VisitResponse(
        Instant visitAtUtc,
        String visitType,
        Double measureValue,
        boolean excludedFromMainAnalysis,
        String exclusionReason) {
}
