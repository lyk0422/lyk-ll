package com.example.starter.cosmetic.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 备案记录（持久化对象的内存镜像，字段口径与 filing_record 表一致）。
 *
 * <p>所有时刻字段均为 UTC；日期字段为 UTC 日界。</p>
 */
public record FilingRecord(
        Long id,
        String filingNo,
        String productCode,
        String productName,
        int versionNo,
        String parentFilingNo,
        ChangeType changeType,
        FilingStatus status,
        List<FormulationIngredient> formulation,
        List<String> claims,
        List<TestReportRecord> reports,
        String packagingText,
        String requestId,
        Instant submittedAtUtc,
        Instant formReviewedAtUtc,
        LocalDate publicNoticeDateUtc,
        Instant publicExpiresAtUtc,
        Instant objectionReceivedAtUtc,
        Instant effectiveAtUtc,
        Instant withdrawnAtUtc,
        String rejectReason
) {
}
