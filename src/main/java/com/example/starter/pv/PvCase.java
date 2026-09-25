package com.example.starter.pv;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 药物警戒个例记录（对应 pv_case 表）。时间均为 UTC。
 */
public record PvCase(
        Long id,
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
        int version) {
}
