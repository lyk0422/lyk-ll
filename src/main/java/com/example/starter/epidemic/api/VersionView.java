package com.example.starter.epidemic.api;

import java.time.OffsetDateTime;

/**
 * 卡片版本视图；所有时间均以 UTC（带 Z 的 ISO-8601）返回。
 */
public record VersionView(
        int versionNo,
        String diseaseCode,
        String diseaseName,
        String diagnosisType,
        String onsetDate,
        OffsetDateTime diagnosisTime,
        OffsetDateTime submittedAt,
        OffsetDateTime deadline,
        boolean timely,
        String lateReason,
        String facilityCode,
        String reporter,
        String changeKind,
        boolean diseaseChanged,
        String notes,
        OffsetDateTime createdAt
) {
}
