package com.example.starter.pv;

import java.time.Instant;

/**
 * 报告提交记录（对应 pv_report 表，初报或随访）。
 */
public record PvReport(
        Long id,
        long caseId,
        String requestId,
        String fingerprint,
        ReportType reportType,
        String narrative,
        boolean late,
        String lateReason,
        Instant submittedAt,
        int caseVersion) {
}
