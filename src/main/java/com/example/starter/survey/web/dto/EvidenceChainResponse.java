package com.example.starter.survey.web.dto;

import com.example.starter.survey.domain.FlagRiskNote;

import java.util.List;

/** 证据链视图：检验 + 互认绑定（含失效历史）+ 风险记录。 */
public record EvidenceChainResponse(
        SurveyResponse survey,
        List<FlagRecognitionResponse> recognitions,
        List<FlagRiskNote> riskNotes
) {
}
