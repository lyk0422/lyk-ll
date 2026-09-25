package com.example.starter.marpol.model;

import com.example.starter.marpol.domain.InspectionState;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 港口国核查（按船舶发起）。
 *
 * @param inspectionDate 核查日（UTC 日界），整改截止日=该日+14 自然日
 * @param issuedAt 签发时刻（UTC），空表示未签发；签发为终态
 */
public record Inspection(
        Long id,
        String inspectionNo,
        Long shipId,
        String officer,
        InspectionState status,
        LocalDate inspectionDate,
        Instant issuedAt,
        Instant createdAt) {
}
