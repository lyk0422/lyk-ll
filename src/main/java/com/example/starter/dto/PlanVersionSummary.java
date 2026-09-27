package com.example.starter.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 日前计划版本摘要（版本列表项）。
 */
public record PlanVersionSummary(
        Long versionId,
        String farmCode,
        LocalDate planDate,
        int versionNo,
        boolean current,
        Instant publishedAt) {
}
