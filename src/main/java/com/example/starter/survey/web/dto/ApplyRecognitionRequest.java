package com.example.starter.survey.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 申请限定范围互认：引用报告、当前本船未签发检验与规则版本。 */
public record ApplyRecognitionRequest(
        @NotBlank String requestId,
        @NotBlank String reportNo,
        @NotNull Long surveyId,
        @NotNull @Positive Integer ruleVersion,
        @NotBlank String itemCode
) {
}
