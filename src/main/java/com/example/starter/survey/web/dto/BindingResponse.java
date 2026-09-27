package com.example.starter.survey.web.dto;

import com.example.starter.survey.domain.RecognitionBinding;

/** 互认绑定视图。 */
public record BindingResponse(
        long id,
        long surveyId,
        String reportNo,
        int revisionNo,
        int ruleVersion,
        String flagItemCode,
        String surveyType,
        String classItemCode,
        String status,
        String invalidatedReason,
        String createdAt,
        String invalidatedAt
) {

    /** 领域绑定转视图。 */
    public static BindingResponse from(RecognitionBinding b) {
        return new BindingResponse(
                b.id(), b.surveyId(), b.reportNo(), b.revisionNo(), b.ruleVersion(),
                b.flagItemCode(), b.surveyType().name(), b.classItemCode(),
                b.status().name(), b.invalidatedReason(),
                b.createdAt() == null ? null : b.createdAt().toString(),
                b.invalidatedAt() == null ? null : b.invalidatedAt().toString());
    }
}
