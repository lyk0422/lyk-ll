package com.example.starter.pv.web;

import java.time.LocalDate;

/**
 * 随访提交结果。
 */
public record FollowUpResponse(
        String caseNo,
        int version,
        LocalDate latestFollowUpDate,
        CaseView caseView) {
}
