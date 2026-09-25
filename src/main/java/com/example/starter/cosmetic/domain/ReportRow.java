package com.example.starter.cosmetic.domain;

import java.time.LocalDate;

/**
 * 功效评价试验报告行，issuedDate 为 UTC 日界。
 */
public record ReportRow(
        long id,
        long registrationId,
        String efficacyCode,
        ReportConclusion conclusion,
        LocalDate issuedDate,
        String lab) {
}
