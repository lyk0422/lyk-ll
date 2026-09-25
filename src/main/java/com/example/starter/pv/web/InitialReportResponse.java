package com.example.starter.pv.web;

/**
 * 初报提交结果。重复个例按随访并入原活动个例时 {@code merged} 为 true。
 */
public record InitialReportResponse(
        String caseNo,
        int version,
        boolean merged,
        boolean onTime,
        CaseView caseView) {
}
