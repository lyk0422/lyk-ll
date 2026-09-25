package com.example.starter.survey.domain;

import com.example.starter.survey.model.SurveyEnums.DefectStatus;
import com.example.starter.survey.model.SurveyEnums.Severity;

import java.time.LocalDate;

/**
 * 缺陷行，构成可复算的缺陷链。
 */
public record DefectRow(
        Long id,
        Long surveyId,
        Severity severity,
        String description,
        DefectStatus status,
        LocalDate foundDate,
        LocalDate dueDate,
        LocalDate rectifiedDate,
        LocalDate recheckSubmitDate,
        LocalDate acceptedDate) {
}
