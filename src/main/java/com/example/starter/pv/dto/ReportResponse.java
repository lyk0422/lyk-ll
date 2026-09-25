package com.example.starter.pv.dto;

import java.time.Instant;

import com.example.starter.pv.CaseStatus;
import com.example.starter.pv.ReportType;

/**
 * 报告提交结果（初报或随访）。
 *
 * @param mergedAsFollowUp 初报请求因已存在活动个例而被并入为随访时为 true
 */
public record ReportResponse(
        long reportId,
        String requestId,
        String caseNo,
        ReportType reportType,
        CaseStatus caseStatus,
        boolean mergedAsFollowUp,
        boolean late,
        String lateReason,
        Instant dueAt,
        Instant submittedAt,
        int caseVersion) {
}
