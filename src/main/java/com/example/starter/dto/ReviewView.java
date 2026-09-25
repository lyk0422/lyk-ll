package com.example.starter.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 审查流程对外视图（资料核对表、检验链、公示记录均可据此复算）。
 */
public record ReviewView(
        Long reviewId,
        String enterpriseName,
        String productName,
        String batchNo,
        String status,
        List<String> documents,
        List<String> missingDocuments,
        Instant declaredAt,
        LocalDate correctionNoticeDate,
        LocalDate correctionDeadline,
        Instant correctedAt,
        LocalDate publicityStartUtc,
        LocalDate publicityEndUtc,
        String conclusion,
        Instant issuedAt,
        List<InspectionView> inspections,
        PublicityView publicity
) {
}
