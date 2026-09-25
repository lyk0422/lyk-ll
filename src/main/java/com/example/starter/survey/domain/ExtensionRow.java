package com.example.starter.survey.domain;

import com.example.starter.survey.model.SurveyEnums.ExtensionStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 展期申请行。一张证书至多一次批准（uk_extension_cert）。
 */
public record ExtensionRow(
        Long id,
        String requestId,
        String approveRequestId,
        Long shipId,
        String typeCode,
        Long certificateId,
        int months,
        ExtensionStatus status,
        LocalDate oldValidUntil,
        LocalDate newValidUntil,
        LocalDateTime appliedAt,
        LocalDateTime decidedAt) {
}
