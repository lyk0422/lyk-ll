package com.example.starter.survey.web.dto;

import com.example.starter.survey.domain.ExtensionRequest;
import com.example.starter.survey.domain.ExtensionStatus;

/** 展期视图。 */
public record ExtensionResponse(
        Long id,
        String requestId,
        Long surveyId,
        Long certificateId,
        int months,
        ExtensionStatus status,
        String oldValidUntil,
        String newValidUntil,
        String appliedAt,
        String decidedAt,
        String note
) {

    public static ExtensionResponse from(ExtensionRequest ext) {
        return new ExtensionResponse(
                ext.id(), ext.requestId(), ext.surveyId(), ext.certificateId(), ext.months(),
                ext.status(),
                ext.oldValidUntil() == null ? null : ext.oldValidUntil().toString(),
                ext.newValidUntil() == null ? null : ext.newValidUntil().toString(),
                ext.appliedAt() == null ? null : ext.appliedAt().toString(),
                ext.decidedAt() == null ? null : ext.decidedAt().toString(),
                ext.note()
        );
    }
}
