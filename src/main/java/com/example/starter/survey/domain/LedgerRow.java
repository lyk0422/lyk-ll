package com.example.starter.survey.domain;

import com.example.starter.survey.model.SurveyEnums.LedgerState;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 年度检验窗口台账行（可由检验单与展期记录复算）。
 */
public record LedgerRow(
        Long id,
        Long shipId,
        int cycleYear,
        LocalDate anniversaryDate,
        LocalDate windowStart,
        LocalDate windowEnd,
        LocalDate extendedEnd,
        Long surveyId,
        LocalDate boardedAt,
        LedgerState state,
        LocalDateTime updatedAt) {
}
