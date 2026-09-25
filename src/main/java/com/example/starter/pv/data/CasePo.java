package com.example.starter.pv.data;

import java.time.Instant;
import java.time.LocalDate;

import com.example.starter.pv.domain.CaseStatus;
import com.example.starter.pv.domain.Seriousness;

/**
 * pv_case 表行对象（个例主表）。
 */
public record CasePo(
        Long id,
        String caseNo,
        String patientCode,
        String patientName,
        String drugCode,
        String drugName,
        String reactionEvent,
        Seriousness seriousness,
        CaseStatus status,
        LocalDate awarenessDate,
        Instant initialSubmittedAt,
        LocalDate initialDueDate,
        boolean onTime,
        String lateReason,
        LocalDate latestFollowUpDate,
        int followUpCount,
        String relatedCaseNo,
        Instant createdAt,
        Instant updatedAt) {
}
