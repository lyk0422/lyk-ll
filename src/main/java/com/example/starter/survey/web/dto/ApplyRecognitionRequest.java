package com.example.starter.survey.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 申请互认：把报告合格项目的完成证明绑定到本船未签发检验；requestId 幂等。
 *
 * @param surveyId    本船未签发检验主键
 * @param reportNo    外部检查报告编号（取当前有效修订）
 * @param ruleVersion 互认规则版本
 */
public record ApplyRecognitionRequest(
        @NotBlank String requestId,
        @NotNull Long surveyId,
        @NotBlank String reportNo,
        @NotNull @Positive Integer ruleVersion
) {
}
