package com.example.starter.survey.web.dto;

import com.example.starter.survey.domain.RecognitionRisk;

/** 互认风险记录视图。 */
public record RiskResponse(
        Long id,
        Long bindingId,
        Long surveyId,
        String reportNo,
        String note,
        String createdAt
) {

    /** 将领域记录转换为视图。 */
    public static RiskResponse from(RecognitionRisk risk) {
        return new RiskResponse(risk.id(), risk.bindingId(), risk.surveyId(),
                risk.reportNo(), risk.note(), risk.createdAt().toString());
    }
}
