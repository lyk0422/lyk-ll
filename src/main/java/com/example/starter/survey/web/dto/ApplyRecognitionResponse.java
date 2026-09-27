package com.example.starter.survey.web.dto;

import java.util.List;

/** 互认申请结果视图。 */
public record ApplyRecognitionResponse(
        Long surveyId,
        List<RecognitionBindingResponse> bindings
) {
}
