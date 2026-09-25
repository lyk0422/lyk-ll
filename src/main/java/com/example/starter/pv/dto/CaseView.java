package com.example.starter.pv.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.example.starter.pv.CaseStatus;
import com.example.starter.pv.ReportType;
import com.example.starter.pv.Seriousness;

/**
 * 个例详情视图，含全部报告记录，时限判定可复算。
 */
public record CaseView(
        String caseNo,
        String patientId,
        String drugName,
        String adverseEvent,
        Seriousness seriousness,
        LocalDate awarenessDate,
        Instant dueAt,
        CaseStatus status,
        Instant initialSubmittedAt,
        Instant lastFollowUpAt,
        Instant closedAt,
        String linkedCaseNo,
        int version,
        List<ReportItem> reports) {

    /**
     * 单条报告记录。
     */
    public record ReportItem(
            long reportId,
            String requestId,
            ReportType reportType,
            boolean late,
            String lateReason,
            Instant submittedAt,
            int caseVersion) {
    }
}
