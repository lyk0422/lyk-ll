package com.example.starter.survey.web.dto;

import com.example.starter.survey.domain.RecognitionRisk;

/** 互认风险记录视图。 */
public record RecognitionRiskResponse(
        Long id,
        Long bindingId,
        String reportNo,
        int newRevision,
        String note,
        String createdAt
) {

    /** 将领域记录转换为视图。 */
    public static RecognitionRiskResponse from(RecognitionRisk risk) {
        return new RecognitionRiskResponse(
                risk.id(), risk.bindingId(), risk.reportNo(), risk.newRevision(), risk.note(),
                risk.createdAt() == null ? null : risk.createdAt().toString());
    }
}
