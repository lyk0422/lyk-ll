package com.example.starter.pv.data;

import java.time.Instant;
import java.time.LocalDate;

/**
 * pv_follow_up 表行对象（随访/并入信息）。
 */
public record FollowUpPo(
        Long id,
        String caseNo,
        int versionNo,
        LocalDate followUpDate,
        String infoText,
        String reporterCode,
        String reporterName,
        String sourceRequestId,
        Instant createdAt) {
}
