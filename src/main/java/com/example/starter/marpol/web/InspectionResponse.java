package com.example.starter.marpol.web;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 核查任务响应视图。
 */
public record InspectionResponse(
        String inspectionNo,
        String shipId,
        String portCode,
        LocalDate inspectDate,
        String status,
        String reviewOf,
        Instant issuedAt,
        Instant createdAt) {
}
