package com.example.starter.marpol.web;

import java.time.Instant;

/**
 * 核查逐条结论响应。
 */
public record InspectionItemResponse(
        Long id,
        String inspectionNo,
        String recordNo,
        Integer recordVersion,
        String conclusion,
        String evidence,
        Instant decidedAt,
        Long defectId,
        String defectStatus,
        java.time.LocalDate defectDeadline) {
}
