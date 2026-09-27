package com.example.starter.survey.web.dto;

import java.util.List;

/** 申请互认结果视图：本次建立的绑定集合（冻结报告修订号与规则版本）。 */
public record ApplyRecognitionResponse(
        Long surveyId,
        String reportNo,
        int reportRevision,
        int ruleVersion,
        List<BindingResponse> bindings
) {
}
