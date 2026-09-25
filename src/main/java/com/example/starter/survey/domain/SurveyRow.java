package com.example.starter.survey.domain;

import com.example.starter.survey.model.SurveyEnums.SurveyStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 检验单行。{@code activeSlot} 为 TRUE 时表示进行中（DB 中为 1，终态置 NULL）。
 */
public record SurveyRow(
        Long id,
        String applyRequestId,
        Long shipId,
        String typeCode,
        SurveyStatus status,
        LocalDate anniversaryDate,
        LocalDate windowStart,
        LocalDate windowEnd,
        int extensionMonths,
        LocalDateTime appliedAt,
        LocalDate boardedAt,
        LocalDate issuedAt,
        Boolean activeSlot,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
