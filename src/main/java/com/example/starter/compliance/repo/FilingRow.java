package com.example.starter.compliance.repo;

/**
 * 量产备案行。
 *
 * @param watchStartDay 复审观察起始 UTC 日（epochDay），null 表示未进入观察；截止日 = 起始日 + 90
 * @param suspendReason 暂停原因：OVERDUE 逾期未复审 / REVIEW_FAILED 复审不通过 / SUPERSEDED 被新版本取代
 */
public record FilingRow(
        Long id,
        Long formulaId,
        String productCode,
        Integer version,
        String status,
        String requestId,
        String snapshot,
        Long watchStartDay,
        String suspendReason,
        long createdAt,
        Long finalizedAt) {
}
