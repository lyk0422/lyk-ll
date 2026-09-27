package com.example.starter.survey.web.dto;

import com.example.starter.survey.domain.FlagRecognition;

/** 互认绑定视图。 */
public record FlagRecognitionResponse(
        Long id,
        String requestId,
        Long surveyId,
        String reportNo,
        Integer revision,
        Integer ruleVersion,
        String itemCode,
        String status,
        String createdAt,
        String invalidatedAt
) {

    public static FlagRecognitionResponse from(FlagRecognition recognition, String reportNo) {
        return new FlagRecognitionResponse(
                recognition.id(), recognition.requestId(), recognition.surveyId(), reportNo,
                recognition.revision(), recognition.ruleVersion(), recognition.itemCode(),
                recognition.status().name(),
                recognition.createdAt() == null ? null : recognition.createdAt().toString(),
                recognition.invalidatedAt() == null ? null : recognition.invalidatedAt().toString());
    }
}
